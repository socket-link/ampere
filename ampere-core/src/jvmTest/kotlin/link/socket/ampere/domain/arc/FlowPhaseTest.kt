package link.socket.ampere.domain.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.definition.SparkAgentFactory
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.time.MutableClock
import okio.Path.Companion.toPath

class FlowPhaseTest {

    @Test
    fun `flow phase executes until max ticks`() = runTest {
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "pm")),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
            ),
        )

        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Test goal",
            ),
        )

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = emptyList(), // Skip agent execution for now
            goalTree = goalTree,
            maxTicks = 3,
        )

        assertEquals(0, flow.getCurrentTick())

        // Flow should fail quickly with empty agents list
        try {
            flow.execute()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("at least one agent") == true)
        }
    }

    @Test
    fun `flow phase initializes with correct state`() {
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
        )

        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Test goal",
            ),
        )

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = emptyList(),
            goalTree = goalTree,
            maxTicks = 100,
        )

        assertEquals(0, flow.getCurrentTick())
        assertEquals(false, flow.isComplete())
    }

    @Test
    fun `flow phase can be stopped manually`() {
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
        )

        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Test goal",
            ),
        )

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = emptyList(),
            goalTree = goalTree,
            maxTicks = 100,
        )

        assertEquals(false, flow.isComplete())
        flow.stop()
        assertTrue(flow.isComplete())
        assertEquals(TerminationReason.MANUAL_STOP, flow.snapshot().terminationReason)
    }

    @Test
    fun `flow phase tick loop bails out when its coroutine is cancelled`() = runTest {
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
            orchestration = OrchestrationConfig(type = OrchestrationType.SEQUENTIAL),
        )

        val goalTree = GoalTree(root = GoalNode(id = "goal-1", description = "Test goal"))

        val agentScope = CoroutineScope(SupervisorJob())
        val agents = try {
            ArcAgentSpawner(
                SparkAgentFactory(
                    scope = agentScope,
                    workspace = ExecutionWorkspace(baseDirectory = "/tmp/ampr300-test-workspace"),
                ),
            ).spawn(
                arcConfig,
                ProjectContext(
                    projectId = "demo",
                    description = "Demo",
                    repositoryRoot = "/tmp".toPath(),
                    architecture = "Layered",
                    conventions = "Kotlin",
                    techStack = listOf("Kotlin"),
                    sources = emptyList(),
                ),
            )
        } finally {
            agentScope.cancel()
        }

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = agents,
            goalTree = goalTree,
            maxTicks = 100,
        )

        // Cancel from inside the coroutine that runs the loop: the very first `ensureActive()`
        // must throw rather than grinding through all 100 ticks.
        val outerJob = Job()
        val thrown = runCatching {
            withContext(outerJob) {
                outerJob.cancel()
                flow.execute()
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException, "Cancellation must propagate, not be swallowed")
        assertEquals(0, flow.getCurrentTick())
        assertEquals(TerminationReason.CANCELLED, flow.snapshot().terminationReason)
    }

    @Test
    fun `agents read the injected clock through the shared context and see it advance per tick`() = runTest {
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
            orchestration = OrchestrationConfig(type = OrchestrationType.SEQUENTIAL),
        )
        val goalTree = GoalTree(root = GoalNode(id = "goal-1", description = "Test goal"))

        val start = Instant.parse("2026-01-01T00:00:00Z")
        val clock = MutableClock(start)
        val agent = ClockReadingAgent()

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = listOf(agent),
            goalTree = goalTree,
            maxTicks = 3,
            clock = clock,
        )

        val recorded = mutableListOf<Instant>()
        agent.onPerceive = {
            recorded += flow.sharedContext.clock.now()
            clock.advance(1.minutes)
        }

        val result = flow.execute()

        assertEquals(TerminationReason.MAX_TICKS_REACHED, result.terminationReason)
        assertEquals(listOf(start, start + 1.minutes, start + 2.minutes), recorded)
    }

    @Test
    fun `each tick plans the Arc's current goal rather than the agent's own task cell`() = runTest {
        // AMPR-395: the only writers of the agent's task cell are its own `executePlan`/
        // `runTask`, neither of which the Arc path calls — so a tick that read the task from
        // there planned `Task.Blank` forever and the user's goal reached no agent.
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
            orchestration = OrchestrationConfig(type = OrchestrationType.SEQUENTIAL),
        )
        val goalTree = GoalTree(root = GoalNode(id = "goal-1", description = "Test goal"))

        val agent = ClockReadingAgent()
        val planned = mutableListOf<Task>()
        agent.onPlan = { planned += it }

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = listOf(agent),
            goalTree = goalTree,
            maxTicks = 2,
        )

        val result = flow.execute()

        assertEquals(TerminationReason.MAX_TICKS_REACHED, result.terminationReason)
        assertEquals(
            listOf("Test goal", "Test goal"),
            planned.map { (it as Task.CodeChange).description },
            "every tick must hand the current goal node to the agent as the task it plans",
        )
        assertEquals(
            listOf("goal-1", "goal-1"),
            planned.map { it.id },
            "the task keeps the goal node's id, so a plan's steps trace back to the goal",
        )
    }

    @Test
    fun `a goal node with no description ends the tick before Perceive`() = runTest {
        // A blank task plans `Plan.blank`, which executes to `Outcome.blank` — never a
        // success. Perceiving first would spend a model call on a tick that cannot progress.
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
            orchestration = OrchestrationConfig(type = OrchestrationType.SEQUENTIAL),
        )
        val goalTree = GoalTree(root = GoalNode(id = "goal-1", description = "  "))

        val agent = ClockReadingAgent()
        var perceptions = 0
        val planned = mutableListOf<Task>()
        agent.onPerceive = { perceptions++ }
        agent.onPlan = { planned += it }

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = listOf(agent),
            goalTree = goalTree,
            maxTicks = 3,
        )

        val result = flow.execute()

        assertEquals(TerminationReason.MAX_TICKS_REACHED, result.terminationReason)
        assertEquals(3, result.finalTick, "the tick budget is still spent; only the calls are not")
        assertEquals(0, perceptions, "a blank task must not reach Perceive")
        assertTrue(planned.isEmpty(), "a blank task must not reach Plan either")
        assertTrue(result.agentOutcomes.isEmpty(), "a tick that never ran records no outcome")
    }

    @Test
    fun `shared context tracks goal completion`() {
        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Root goal",
                children = listOf(
                    GoalNode(id = "goal-2", description = "Child goal 1"),
                    GoalNode(id = "goal-3", description = "Child goal 2"),
                ),
            ),
        )

        val context = SharedContext(
            goalTree = goalTree,
            currentGoal = goalTree.root,
        )

        assertEquals(false, context.isGoalTreeComplete())

        // Mark goals as complete
        context.markGoalComplete(goalTree.root)
        context.markGoalComplete(goalTree.root.children[0])
        context.markGoalComplete(goalTree.root.children[1])

        assertTrue(context.isGoalTreeComplete())

        // Every agent in a tick works the same current goal and marks it on success, so the
        // second and third marks of one node must not inflate the count (AMPR-395).
        context.markGoalComplete(goalTree.root)
        assertEquals(3, context.completedGoals.size, "marking a goal complete twice is a no-op")
    }

    @Test
    fun `goal tree allNodes returns all nodes`() {
        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Root",
                children = listOf(
                    GoalNode(id = "goal-2", description = "Child 1"),
                    GoalNode(
                        id = "goal-3",
                        description = "Child 2",
                        children = listOf(
                            GoalNode(id = "goal-4", description = "Grandchild"),
                        ),
                    ),
                ),
            ),
        )

        val allNodes = goalTree.allNodes()
        assertEquals(4, allNodes.size)
        assertTrue(allNodes.any { it.id == "goal-1" })
        assertTrue(allNodes.any { it.id == "goal-2" })
        assertTrue(allNodes.any { it.id == "goal-3" })
        assertTrue(allNodes.any { it.id == "goal-4" })
    }

    /**
     * An agent that never completes a goal, runs [onPerceive] at the top of each of its ticks,
     * and reports the task the tick handed it to [onPlan].
     */
    private class ClockReadingAgent : AutonomousAgent<AgentState>() {
        var onPerceive: () -> Unit = {}
        var onPlan: (Task) -> Unit = {}

        override val id: AgentId = "ClockReadingAgent"
        override val initialState: AgentState = AgentState()
        override val agentConfiguration = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = UnusedAIConfiguration,
        )

        override val runLLMToEvaluatePerception: (perception: Perception<AgentState>) -> Idea =
            { _ -> Idea.blank }
        override val runLLMToPlan: (
            task: Task,
            ideas: List<Idea>,
            relevantKnowledge: List<KnowledgeWithScore>,
        ) -> Plan = { task, _, _ ->
            onPlan(task)
            Plan.blank
        }
        override val runLLMToExecuteTask: (task: Task, priorResults: List<StepOutcome>) -> Outcome =
            { _, _ -> Outcome.blank }
        override val runLLMToExecuteTool: (tool: Tool<*>, request: ExecutionRequest<*>) -> ExecutionOutcome =
            { _, _ -> error("No tools in this test") }
        override val runLLMToEvaluateOutcomes: (outcomes: List<Outcome>) -> Idea = { _ -> Idea.blank }

        override fun callLLM(prompt: String): String = error("No LLM in this test")

        override fun extractKnowledgeFromOutcome(outcome: Outcome, task: Task, plan: Plan): Knowledge =
            error("No outcomes are learned from in this test")

        override suspend fun perceiveState(
            currentState: AgentState,
            vararg newIdeas: Idea,
        ): Perception<AgentState> {
            onPerceive()
            return super.perceiveState(currentState, *newIdeas)
        }
    }

    private object UnusedAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Not needed for tests")
        override val model: AIModel
            get() = throw NotImplementedError("Not needed for tests")
        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }
}
