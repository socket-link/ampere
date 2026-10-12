package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.arc.ArcAgentConfig
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.FlowPhase
import link.socket.ampere.domain.arc.GoalNode
import link.socket.ampere.domain.arc.GoalTree
import link.socket.ampere.domain.arc.OrchestrationConfig
import link.socket.ampere.domain.arc.OrchestrationType
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-442 (H19): one loop. Step dispatch, goal completion and one agent's tick each exist
 * once in `propel/`, and both drivers call them.
 *
 * The central case puts the same step through both drivers: one tool step that succeeds,
 * then the same step against a tool that fails. It asserts that the Arc's `FlowPhase` and a
 * roster-hosted run give the same completion answer each time. On the Arc that answer
 * marks the goal complete. On a hosted run it is recorded and nothing else.
 *
 * Driven through the production wiring: real agents, a real `FunctionExecutor`, and the
 * real phase services behind a counting fake transport. `runBlocking`, not `runTest`: a
 * door's publish hops to the IO dispatcher and a virtual clock walks straight past it.
 */
class OneLoopTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    // ========================================================================
    // Goal completion
    // ========================================================================

    @Test
    fun `the default completion answers yes to a success and no to everything else`() = runBlocking<Unit> {
        val now = Clock.System.now()
        val answers = listOf(
            ExecutionOutcome.NoChanges.Success(EXECUTOR, TICKET, TASK, now, now, "done"),
            ExecutionOutcome.NoChanges.Failure(EXECUTOR, TICKET, TASK, now, now, "failed"),
            ExecutionOutcome.Held(EXECUTOR, TICKET, TASK, now, now, reason = "waiting on a person"),
            Outcome.blank,
        ).map { GoalCompletion.AnySuccess.isComplete(it) }

        assertEquals(
            listOf(true, false, false, false),
            answers,
            "any success completes the goal, which is the rule FlowPhase inlined; a hold, a " +
                "failure and a blank do not",
        )
    }

    @Test
    fun `the same step gives the same completion answer through both drivers`() = runBlocking<Unit> {
        val arc = listOf(
            arcCompletes(recordingTool(TOOL, TOOL_RESULT, dispatched)),
            arcCompletes(failingTool(TOOL, TOOL_ERROR, dispatched)),
        )
        val hosted = listOf(
            hostedCompletes(recordingTool(TOOL, TOOL_RESULT, dispatched)),
            hostedCompletes(failingTool(TOOL, TOOL_ERROR, dispatched)),
        )

        assertEquals(4, dispatched.size, "each driver dispatched the step's tool once per run")
        assertEquals(
            listOf(true, false),
            arc,
            "the Arc completes its goal on the step that succeeded and not on the one that failed",
        )
        assertEquals(arc, hosted, "and a hosted run answers the same question the same way")
    }

    @Test
    fun `a hosted run records its completion answers and does not end on them`() = runBlocking<Unit> {
        val run = openRun(recordingTool(TOOL, TOOL_RESULT, dispatched), policy = RunPolicy(cycles = 2))
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(
            2,
            result.cyclesUsed,
            "the first cycle's yes did not end the run; it planned and executed its second",
        )
        assertEquals(
            listOf(true, true),
            assertIs<RosterHostedRun>(run).goalCompletionAnswers,
            "one answer per executed cycle, in order",
        )
    }

    // ========================================================================
    // The tick
    // ========================================================================

    @Test
    fun `a tick runs perceive then plan then execute on the assigned task`() = runBlocking<Unit> {
        val agent = bareAgent()
        val phases = RecordingTickPhases(agent)

        val outcome = runAgentTick(agent, GOAL_TASK, phases)

        assertSame(phases.outcome, outcome, "the tick returns what EXECUTE settled to")
        assertEquals(
            listOf("perceive", "plan ${GOAL_TASK.id} ideas=1 knowledge=0", "execute ${phases.plan.id}"),
            phases.calls,
            "PERCEIVE's ideas and RECALL's knowledge reach PLAN, and PLAN's plan reaches EXECUTE",
        )
        assertEquals(
            GOAL_TASK,
            phases.taskSeenByPerceive,
            "the task is assigned before PERCEIVE, which reads it off the agent's memory cell",
        )
        assertIs<Task.Blank>(
            agent.getCurrentState().getCurrentMemory().task,
            "and released before EXECUTE, so the task's spark is not stranded",
        )
    }

    @Test
    fun `a blank task ends the tick before any call`() = runBlocking<Unit> {
        val agent = bareAgent()
        val phases = RecordingTickPhases(agent)

        assertNull(runAgentTick(agent, Task.Blank, phases))
        assertTrue(phases.calls.isEmpty(), "a blank task reaches no phase; got ${phases.calls}")
    }

    @Test
    fun `a tick whose plan throws still releases the assignment`() = runBlocking<Unit> {
        val agent = bareAgent()
        val phases = RecordingTickPhases(agent, planFails = true)

        assertFailsWith<IllegalStateException> { runAgentTick(agent, GOAL_TASK, phases) }

        assertTrue(phases.calls.none { it.startsWith("execute") }, "nothing was executed")
        assertIs<Task.Blank>(agent.getCurrentState().getCurrentMemory().task)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /** One Flow tick over one agent whose plan is a single step nominating [tool]. */
    private suspend fun arcCompletes(tool: Tool<*>): Boolean {
        val agent = SparkBasedAgent(
            agentId = ARC_AGENT_ID,
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = AgentState(),
            _additionalTools = setOf(tool),
            _aiConfiguration = AI_CONFIGURATION,
            _upstreamLlmClient = CountingUpstreamLlmClient(
                hostAnswers { planJson(planStep(STEP_TEXT, TOOL, seat = null)) },
            ),
            _observabilityScope = scope,
            _executor = FunctionExecutor.create(),
        )
        val flow = FlowPhase(
            arcConfig = ARC_CONFIG,
            agents = listOf(agent),
            goalTree = GoalTree(root = GoalNode(id = GOAL_ID, description = GOAL_TEXT)),
            maxTicks = 1,
        )
        val result = flow.execute()
        return result.completedGoals.any { it.id == GOAL_ID }
    }

    /** One hosted run whose plan is the same step, placed with the doer seat. */
    private suspend fun hostedCompletes(tool: Tool<*>): Boolean {
        val run = openRun(tool, policy = RunPolicy())
        run.execute()
        run.close()
        return assertIs<RosterHostedRun>(run).goalCompletionAnswers.single()
    }

    private suspend fun openRun(tool: Tool<*>, policy: RunPolicy): HostedRun =
        rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
            roster = RosterConfig(
                host = HOST_ROLE,
                roles = listOf(
                    RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
                    RoleConfig(id = DOER_ROLE, title = "Doer", instructions = PROMPT, tools = setOf(TOOL)),
                ),
            ),
            seats = mapOf(
                HOST_ROLE to seat(HOST_ID, HOST_ROLE, hostAnswers { hostedPlan() }),
                DOER_ROLE to seat(DOER_ID, DOER_ROLE, fixedAnswer { "" }),
            ),
            goal = goal(GOAL_TEXT, id = GOAL_ID),
            tools = setOf(tool),
            policy = policy,
        )

    private fun seat(id: String, role: RoleId, answer: (String) -> String): HostedAgent = HostedAgent(
        id = id,
        role = role,
        charter = "You are the ${role.value} of this run.",
        aiConfiguration = AI_CONFIGURATION,
        upstreamLlmClient = CountingUpstreamLlmClient(answer),
    )

    private fun hostedPlan(): String =
        planJson(planStep(STEP_TEXT, SeatTool(DOER_ROLE, TOOL).id, DOER_ROLE.value))

    /** An agent with no transport: the tick tests supply every call that would need one. */
    private fun bareAgent(): SparkBasedAgent<AgentState> = SparkBasedAgent(
        agentId = "one-loop-bare-agent",
        cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
        initialState = AgentState(),
        _aiConfiguration = AI_CONFIGURATION,
        _observabilityScope = scope,
    )

    /**
     * The driver's half of a tick, recording each call. Captures the task [agent]'s memory
     * cell held when PERCEIVE ran, which is what `defaultPerceptionContext` renders.
     */
    private class RecordingTickPhases(
        private val agent: SparkBasedAgent<AgentState>,
        private val planFails: Boolean = false,
    ) : TickPhases {
        val calls = mutableListOf<String>()
        var taskSeenByPerceive: Task? = null

        val plan: Plan = Plan.ForTask(task = GOAL_TASK, tasks = emptyList(), estimatedComplexity = 1)
        val outcome: Outcome = Outcome.blank

        override suspend fun perceive(): List<Idea> {
            calls += "perceive"
            taskSeenByPerceive = agent.getCurrentState().getCurrentMemory().task
            return listOf(Idea(name = "an idea"))
        }

        override suspend fun plan(
            task: Task,
            ideas: List<Idea>,
            relevantKnowledge: List<KnowledgeWithScore>,
        ): Plan {
            calls += "plan ${task.id} ideas=${ideas.size} knowledge=${relevantKnowledge.size}"
            check(!planFails) { "the planner failed" }
            return plan
        }

        override suspend fun execute(plan: Plan): Outcome {
            calls += "execute ${plan.id}"
            return outcome
        }
    }

    private companion object {
        const val SDK_ID = "one-loop-test-sdk"
        const val ARC_AGENT_ID = "one-loop-arc-agent"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val TOOL = "search"
        const val TOOL_RESULT = "found the retry policy in Uploader.kt"
        const val TOOL_ERROR = "the index is unavailable"
        const val GOAL_ID = "goal-one-loop"
        const val GOAL_TEXT = "Add a retry to the uploader"
        const val STEP_TEXT = "Search for the retry policy"

        const val EXECUTOR = "one-loop-executor"
        const val TICKET = "one-loop-ticket"
        const val TASK = "one-loop-task"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )

        val GOAL_TASK = Task.CodeChange(id = GOAL_ID, status = TaskStatus.Pending, description = GOAL_TEXT)

        val ARC_CONFIG = ArcConfig(
            name = "one-loop-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
            orchestration = OrchestrationConfig(type = OrchestrationType.SEQUENTIAL),
        )
    }
}
