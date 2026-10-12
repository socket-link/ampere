package link.socket.ampere.propel

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatRole
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * A later cycle plans with the results so far (AMPR-424, AMPR-385 row H28).
 *
 * Before this, a two-cycle run's second PLAN was the first one again — same task,
 * same ideas, same recall, nothing about what the first cycle did — so it planned
 * the same work twice, could not say "nothing left to do" (an empty `steps` array was
 * a parse failure that became a one-step fallback plan), and gave its steps the first
 * cycle's ids. These tests drive the production wiring, as [HostedRunTest] does, and
 * read the prompts the host's transport was sent.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a
 * virtual clock walks straight past it.
 */
class HostedRunCycleTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var store: MemoryStore

    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
        store = memoryStoreOf(
            knowledge = KnowledgeRepositoryImpl(door.database, door.driver),
            outcomes = OutcomeMemoryRepositoryImpl(door.database),
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `the second PLAN prompt contains the first cycle's step results, and the first does not`() =
        runBlocking<Unit> {
            val host = seat(HOST_ID, HOST_ROLE) {
                planJson(planStep(SEARCH_STEP, DOER_TOOL.seatId(), DOER_ROLE.value))
            }
            val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

            val run = openRun(host, doer, policy = RunPolicy(cycles = 2))
            val result = run.execute()
            run.close()

            assertEquals(2, result.cyclesUsed)
            val planPrompts = host.planPrompts()
            assertEquals(2, planPrompts.size, "two cycles, two PLAN calls")

            assertFalse(
                planPrompts[0].contains(COMPLETED_WORK_HEADING),
                "a first cycle has no earlier work to be shown: ${planPrompts[0]}",
            )
            assertTrue(
                planPrompts[1].contains(COMPLETED_WORK_HEADING),
                "the second cycle is shown what the first did: ${planPrompts[1]}",
            )
            assertTrue(
                planPrompts[1].contains("[succeeded] $SEARCH_STEP"),
                "with the step's verdict and description",
            )
            assertTrue(planPrompts[1].contains(TOOL_RESULT), "and what the tool said")

            // Earlier results are data: in the user half of the prompt, never the system message.
            val second = host.planRequests()[1]
            assertTrue(
                second.messages.filter { it.role == ChatRole.System }
                    .none { it.content.orEmpty().contains(COMPLETED_WORK_HEADING) },
                "the block is not in the system message",
            )
            assertTrue(
                second.messages.filter { it.role == ChatRole.User }
                    .any { it.content.orEmpty().contains(COMPLETED_WORK_HEADING) },
                "the block is in the user message",
            )
        }

    @Test
    fun `a failed first step is in the second prompt`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(planStep(SEARCH_STEP, DOER_TOOL.seatId(), DOER_ROLE.value))
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(
            host,
            doer,
            policy = RunPolicy(cycles = 2),
            tools = setOf(failingTool(DOER_TOOL, FAILURE_TEXT, dispatched)),
        )
        val result = run.execute()
        run.close()

        val second = host.planPrompts()[1]
        assertTrue(second.contains("[failed] $SEARCH_STEP"), "the failure is reported, not dropped: $second")
        assertTrue(second.contains(FAILURE_TEXT), "with its own reason, so the planner can plan around it")
        assertEquals(RunStatus.Failed, result.status)
    }

    @Test
    fun `a second cycle that answers with no steps ends the run`() = runBlocking<Unit> {
        var planCalls = 0
        val host = seat(HOST_ID, HOST_ROLE) {
            if (planCalls++ == 0) {
                planJson(planStep(SEARCH_STEP, DOER_TOOL.seatId(), DOER_ROLE.value))
            } else {
                planJson()
            }
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer, policy = RunPolicy(cycles = 3))
        val result = run.execute()
        run.close()

        assertEquals(2, host.planCalls(), "the cap was 3; the planner ended the run at its second PLAN")
        assertEquals(2, result.cyclesUsed, "and that final PLAN counts as a cycle")
        assertEquals(1, dispatched.size, "nothing was dispatched after the empty plan")
        assertEquals(1, result.stepOutcomes.size)
        assertEquals(RunStatus.Succeeded, result.status, "the status is what the first cycle's steps made it")
        assertTrue(run.plan.tasks.isEmpty(), "the run's plan is the empty one the planner answered with")
        assertTrue(
            host.transport.prompts.none { it.contains("Advanced planning unavailable") },
            "no fallback step was planned, so none was carried out as a model call",
        )
    }

    @Test
    fun `an empty later plan is not sent to the plan gate`() = runBlocking<Unit> {
        var planCalls = 0
        val host = seat(HOST_ID, HOST_ROLE) {
            if (planCalls++ == 0) {
                planJson(planStep(SEARCH_STEP, DOER_TOOL.seatId(), DOER_ROLE.value))
            } else {
                planJson()
            }
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }
        val gate = RecordingGate()

        val run = openRun(host, doer, policy = RunPolicy(cycles = 3, planGate = gate))
        val result = run.execute()
        run.close()

        assertEquals(2, result.cyclesUsed)
        assertEquals(
            1,
            gate.reviewed.size,
            "the gate saw the first plan and nothing after it: there was nothing to approve",
        )
        assertEquals(1, gate.reviewed.single().tasks.size)
    }

    @Test
    fun `no two steps of a two-cycle run share an id, and PlanStepStarted ids are distinct`() =
        runBlocking<Unit> {
            val host = seat(HOST_ID, HOST_ROLE) {
                planJson(
                    planStep(SEARCH_STEP, DOER_TOOL.seatId(), DOER_ROLE.value),
                    planStep("Search again", DOER_TOOL.seatId(), DOER_ROLE.value),
                )
            }
            val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

            val run = openRun(host, doer, policy = RunPolicy(cycles = 2))
            val result = run.execute()
            run.close()

            assertEquals(4, result.stepOutcomes.size, "two steps per cycle, two cycles")
            assertEquals(
                listOf("c2-step-1-$GOAL_ID", "c2-step-2-$GOAL_ID"),
                run.plan.tasks.map { it.id },
                "the latest plan's steps carry the cycle",
            )

            val rows = door.database.eventStoreQueries.getEventsByRunId(run.runId).executeAsList()
            val startedIds = rows
                .mapNotNull { decode(it) as? PlanEvent.PlanStepStarted }
                .map { it.stepId }
            assertEquals(4, startedIds.size, "one started pair per step")
            assertEquals(
                setOf("step-1-$GOAL_ID", "step-2-$GOAL_ID", "c2-step-1-$GOAL_ID", "c2-step-2-$GOAL_ID"),
                startedIds.toSet(),
                "cycle 1 keeps its ids; cycle 2's are its own",
            )
        }

    // ========================================================================
    // Fixture
    // ========================================================================

    private class Seat(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    ) {
        /** Every PLAN prompt this seat was sent, in order, told by the prompt's own schema. */
        fun planPrompts(): List<String> = transport.prompts.filter { it.isPlanPrompt() }

        fun planCalls(): Int = planPrompts().size

        /** The same PLAN calls as requests, so a test can tell the system half from the user half. */
        fun planRequests(): List<ChatCompletionRequest> = transport.requests.filter { request ->
            request.messages.joinToString("\n") { it.content.orEmpty() }.isPlanPrompt()
        }

        private fun String.isPlanPrompt(): Boolean = contains("\"steps\"")
    }

    /** A gate that approves every plan and remembers each one it was shown. */
    private class RecordingGate : PlanGate {
        val reviewed = CopyOnWriteArrayList<Plan>()

        override suspend fun review(plan: Plan): PlanDecision {
            reviewed += plan
            return PlanDecision.Approved(plan)
        }
    }

    private fun seat(
        id: String,
        role: RoleId,
        answering: () -> String,
    ): Seat {
        val transport = CountingUpstreamLlmClient(
            if (role == HOST_ROLE) hostAnswers(answering) else fixedAnswer(answering),
        )
        return Seat(
            descriptor = HostedAgent(
                id = id,
                role = role,
                charter = "You are the ${role.value} of this run.",
                aiConfiguration = AI_CONFIGURATION,
                upstreamLlmClient = transport,
                memory = store,
            ),
            transport = transport,
        )
    }

    private suspend fun openRun(
        host: Seat,
        doer: Seat,
        policy: RunPolicy,
        tools: Set<Tool<*>> = setOf(recordingTool(DOER_TOOL, TOOL_RESULT, dispatched)),
    ): HostedRun = rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
        roster = RosterConfig(
            host = HOST_ROLE,
            roles = listOf(
                RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
                RoleConfig(id = DOER_ROLE, title = "Doer", instructions = PROMPT, tools = setOf(DOER_TOOL)),
            ),
        ),
        seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer.descriptor),
        goal = goal(GOAL_TEXT, id = GOAL_ID),
        tools = tools,
        policy = policy,
    )

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    /** The id this run offers the planner for a tool of the doer seat: `<seat>/<tool>`. */
    private fun String.seatId(): String = SeatTool(DOER_ROLE, this).id

    private companion object {
        const val SDK_ID = "hosted-run-cycle-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val DOER_TOOL = "search"
        const val SEARCH_STEP = "Search for the retry policy"
        const val TOOL_RESULT = "found the retry policy in Uploader.kt"
        const val FAILURE_TEXT = "the index is unreachable"
        const val GOAL_TEXT = "Add a retry to the uploader"
        const val GOAL_ID = "goal-ampr-424"

        const val COMPLETED_WORK_HEADING = "Work already done toward this task, oldest first."

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
