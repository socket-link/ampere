package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.outcome.StepOutcome
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
 * AMPR-450 (AMPR-385 row H20 (a) and (b), as ruled on 2026-10-11): a failed step skips
 * only its dependents, and a run with some steps failed ends [RunStatus.Partial].
 *
 * A step's dependency is the planner's `requiresPreviousStep`, read back into
 * `Task.Step.dependsOn`. When a step fails, every step that depends on it — directly or
 * through another skipped step — is `Skipped` with the failed step's id as its reason,
 * and every independent step still runs. LEARN records the outcome of a partial run and
 * stores no Knowledge from it.
 *
 * Driven through the production wiring like [HostedRunHeldTest] — real doors onto one
 * store, a real `FunctionExecutor`, real phase services behind counting fakes.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a
 * virtual clock walks straight past it.
 */
class HostedRunDependentsTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var store: MemoryStore

    /** Requests each tool was dispatched with, by bare tool id. */
    private val dispatched: Map<String, MutableList<ExecutionRequest<*>>> =
        listOf(FAILING_TOOL, DRAFT_TOOL, SEND_TOOL, SEARCH_TOOL)
            .associateWith { CopyOnWriteArrayList<ExecutionRequest<*>>() }

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
    fun `a failed step skips its dependent and the independent step still runs`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(
                step(FAILING_STEP, FAILING_TOOL, requiresPrevious = false),
                step(DEPENDENT_STEP, DRAFT_TOOL, requiresPrevious = true),
                step(INDEPENDENT_STEP, SEARCH_TOOL, requiresPrevious = false),
            )
        }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer)
        val result = run.execute()
        run.close()

        assertEquals(3, result.stepOutcomes.size)
        val failed = assertIs<StepOutcome.Failure>(result.stepOutcomes[0], "A failed")
        val skipped = assertIs<StepOutcome.Skipped>(result.stepOutcomes[1], "B depends on A")
        assertEquals("depends on failed step ${failed.id}", skipped.reason)
        assertIs<StepOutcome.Success>(result.stepOutcomes[2], "C is independent, so it ran")

        assertEquals(
            mapOf(FAILING_TOOL to 1, DRAFT_TOOL to 0, SEND_TOOL to 0, SEARCH_TOOL to 1),
            dispatchCounts(),
            "the dependent's tool was never dispatched; the independent one was",
        )
        assertEquals(RunStatus.Partial, result.status)
    }

    @Test
    fun `a partial run records its outcome and stores no knowledge`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(
                step(FAILING_STEP, FAILING_TOOL, requiresPrevious = false),
                step(DEPENDENT_STEP, DRAFT_TOOL, requiresPrevious = true),
                step(INDEPENDENT_STEP, SEARCH_TOOL, requiresPrevious = false),
            )
        }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer)
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Partial, result.status)
        assertTrue(
            door.database.knowledgeStoreQueries.findKnowledgeByRunId(run.runId).executeAsList().isEmpty(),
            "no seat stores Knowledge from a partial run",
        )
        assertTrue(events(run.runId).none { it is MemoryEvent.KnowledgeStored })
        assertEquals(
            1,
            store.outcomes.getOutcomesByTicket(run.runId).getOrThrow().size,
            "the outcome is recorded for every run",
        )
        assertTrue(
            events(run.runId).any { it is TaskEvent.TaskFailed },
            "some of the work failed, so the run's task did",
        )
    }

    @Test
    fun `a dependent of a skipped step is skipped too and names the step that failed`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(
                step(FAILING_STEP, FAILING_TOOL, requiresPrevious = false),
                step(DEPENDENT_STEP, DRAFT_TOOL, requiresPrevious = true),
                step("Send the draft", SEND_TOOL, requiresPrevious = true),
                step(INDEPENDENT_STEP, SEARCH_TOOL, requiresPrevious = false),
            )
        }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer)
        val result = run.execute()
        run.close()

        val failedId = assertIs<StepOutcome.Failure>(result.stepOutcomes[0]).id
        listOf(1, 2).forEach { index ->
            val skipped = assertIs<StepOutcome.Skipped>(result.stepOutcomes[index], "step ${index + 1}")
            assertEquals("depends on failed step $failedId", skipped.reason)
        }
        assertIs<StepOutcome.Success>(result.stepOutcomes[3])
        assertEquals(
            mapOf(FAILING_TOOL to 1, DRAFT_TOOL to 0, SEND_TOOL to 0, SEARCH_TOOL to 1),
            dispatchCounts(),
        )
        assertEquals(RunStatus.Partial, result.status)
    }

    @Test
    fun `a skipped dependent's pair leaves through the host's door`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(
                step(FAILING_STEP, FAILING_TOOL, requiresPrevious = false),
                step(DEPENDENT_STEP, DRAFT_TOOL, requiresPrevious = true),
            )
        }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer)
        run.execute()
        run.close()

        val completed = events(run.runId).filterIsInstance<PlanEvent.PlanStepCompleted>()
        assertEquals(DOER_ID, completed.single { it.stepIndex == 0 }.eventSource.getIdentifier(), "A ran on its seat")
        val skipped = completed.single { it.stepIndex == 1 }
        assertIs<StepOutcome.Skipped>(skipped.outcome)
        assertEquals(
            HOST_ID,
            skipped.eventSource.getIdentifier(),
            "the skip is the run's decision, not work the assigned seat did",
        )
    }

    @Test
    fun `every step succeeding is Succeeded and stores knowledge`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(
                step(INDEPENDENT_STEP, SEARCH_TOOL, requiresPrevious = false),
                step(DEPENDENT_STEP, DRAFT_TOOL, requiresPrevious = true),
            )
        }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer)
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertTrue(result.stepOutcomes.all { it is StepOutcome.Success })
        assertTrue(events(run.runId).any { it is MemoryEvent.KnowledgeStored }, "a succeeded run learns")
    }

    @Test
    fun `every step failing is Failed and stores no knowledge`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(
                step(FAILING_STEP, FAILING_TOOL, requiresPrevious = false),
                step("Look it up again", FAILING_TOOL, requiresPrevious = false),
            )
        }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer)
        val result = run.execute()
        run.close()

        assertEquals(
            2,
            dispatched.getValue(FAILING_TOOL).size,
            "the second step is independent, so it ran after the first failed",
        )
        assertTrue(result.stepOutcomes.all { it is StepOutcome.Failure })
        assertEquals(RunStatus.Failed, result.status)
        assertTrue(events(run.runId).none { it is MemoryEvent.KnowledgeStored })
        assertEquals(1, store.outcomes.getOutcomesByTicket(run.runId).getOrThrow().size)
    }

    @Test
    fun `a failed step with only dependents after it is Failed`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(
                step(FAILING_STEP, FAILING_TOOL, requiresPrevious = false),
                step(DEPENDENT_STEP, DRAFT_TOOL, requiresPrevious = true),
            )
        }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer)
        val result = run.execute()
        run.close()

        assertIs<StepOutcome.Skipped>(result.stepOutcomes[1])
        assertEquals(RunStatus.Failed, result.status, "nothing succeeded, so nothing is partial")
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private class Seat(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    )

    private fun seat(id: String, role: RoleId, answering: () -> String): Seat {
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

    private suspend fun openRun(host: Seat, doer: Seat): HostedRun =
        rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
            roster = RosterConfig(
                host = HOST_ROLE,
                roles = listOf(
                    RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
                    RoleConfig(
                        id = DOER_ROLE,
                        title = "Doer",
                        instructions = PROMPT,
                        tools = setOf(FAILING_TOOL, DRAFT_TOOL, SEND_TOOL, SEARCH_TOOL),
                    ),
                ),
            ),
            seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer.descriptor),
            goal = goal(GOAL_TEXT, id = GOAL_ID),
            tools = setOf<Tool<*>>(
                failingTool(FAILING_TOOL, "the index is unreachable", dispatched.getValue(FAILING_TOOL)),
                recordingTool(DRAFT_TOOL, "drafted", dispatched.getValue(DRAFT_TOOL)),
                recordingTool(SEND_TOOL, "sent", dispatched.getValue(SEND_TOOL)),
                recordingTool(SEARCH_TOOL, "found it", dispatched.getValue(SEARCH_TOOL)),
            ),
            policy = RunPolicy(),
        )

    /** One plan step on the doer seat, with its `requiresPreviousStep` set. */
    private fun step(description: String, tool: String, requiresPrevious: Boolean): String = buildString {
        append("{")
        append("\"description\": \"$description\", ")
        append("\"toolToUse\": \"${SeatTool(DOER_ROLE, tool).id}\", ")
        append("\"seat\": \"${DOER_ROLE.value}\", ")
        append("\"requiresPreviousStep\": $requiresPrevious")
        append("}")
    }

    private fun dispatchCounts(): Map<String, Int> = dispatched.mapValues { it.value.size }

    private fun rows(runId: String): List<EventStore> =
        door.database.eventStoreQueries.getEventsByRunId(runId).executeAsList()

    private fun events(runId: String): List<Event> = rows(runId).map(::decode)

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    private companion object {
        const val SDK_ID = "hosted-run-dependents-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val FAILING_TOOL = "lookup"
        const val DRAFT_TOOL = "draft_reply"
        const val SEND_TOOL = "send_reply"
        const val SEARCH_TOOL = "search"
        const val FAILING_STEP = "Look up the thread"
        const val DEPENDENT_STEP = "Draft the reply"
        const val INDEPENDENT_STEP = "Search the archive"
        const val GOAL_TEXT = "Answer the thread"
        const val GOAL_ID = "goal-ampr-450"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
