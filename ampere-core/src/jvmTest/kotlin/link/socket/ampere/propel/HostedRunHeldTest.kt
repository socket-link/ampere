package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.llm.decide.DeterministicDecisionClient
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-425 (AMPR-385 row H20, as ruled on 2026-10-11): held is a full stop.
 *
 * A tool can report that its action is waiting on a person. The step ends Held,
 * `PlanExecutor` skips every later step of the cycle, the run plans no further cycle
 * whatever `cycles` allows, asks nothing about the held cycle, and ends
 * [RunStatus.Held]. LEARN is entered and records nothing — no outcome row, no
 * Knowledge, no model call — and the run's task is left blocked rather than ended,
 * because the run is waiting, not finished.
 *
 * Driven through the production wiring like [HostedRunTest] — real doors onto one
 * store, a real `FunctionExecutor`, real phase services behind counting fakes —
 * because the hold has to survive every layer between the tool and the run.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a
 * virtual clock walks straight past it.
 */
class HostedRunHeldTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var store: MemoryStore

    /** Requests the search and lookup tools were dispatched with. */
    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    /** Requests the holding tool was dispatched with. */
    private val heldDispatches = CopyOnWriteArrayList<ExecutionRequest<*>>()

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
    fun `a held step skips every later step and the run ends Held`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) { planJson(sendStep(), searchStep()) }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer)
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Held, result.status)
        assertEquals(2, result.stepOutcomes.size, "the held step and the one after it")
        assertIs<StepOutcome.Held>(result.stepOutcomes[0])
        val skipped = assertIs<StepOutcome.Skipped>(result.stepOutcomes[1])
        assertEquals("Skipped: step 1 is held ($REASON)", skipped.reason)
        assertEquals(1, heldDispatches.size, "the held tool ran once")
        assertTrue(dispatched.isEmpty(), "and the search after it never ran")
    }

    @Test
    fun `a held first cycle plans no second cycle, whatever the cap`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) { planJson(sendStep()) }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer, policy = RunPolicy(cycles = 3))
        val result = run.execute()
        run.close()

        assertEquals(1, host.planCalls(), "one PLAN — the one open charged — and none after the hold")
        assertEquals(1, result.cyclesUsed)
        assertEquals(RunStatus.Held, result.status)

        val observes = hostBrackets(run.runId).count { it == CognitivePhase.OBSERVE }
        assertEquals(1, observes, "the charge's OBSERVE, and no OBSERVE after the held cycle")
    }

    @Test
    fun `the held step's reason and reference are on its PlanStepCompleted and on the run result`() =
        runBlocking<Unit> {
            val host = seat(HOST_ID, HOST_ROLE) { planJson(sendStep()) }
            val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

            val run = openRun(host, doer)
            val result = run.execute()
            run.close()

            val completed = events(run.runId).filterIsInstance<PlanEvent.PlanStepCompleted>().single()
            val stepHeld = assertIs<StepOutcome.Held>(completed.outcome)
            assertEquals(REASON, stepHeld.reason)
            assertEquals(REFERENCE, stepHeld.reference)
            assertEquals(DOER_ID, completed.eventSource.getIdentifier(), "published by the seat that ran it")
            assertTrue(completed.getSummary({ "" }, { "" }).contains("held"), "and its summary says held")

            val runHeld = assertIs<ExecutionOutcome.Held>(result.outcome)
            assertEquals(REASON, runHeld.reason)
            assertEquals(REFERENCE, runHeld.reference)

            val toolCompleted = events(run.runId).filterIsInstance<ToolEvent.ToolExecutionCompleted>().single()
            assertTrue(toolCompleted.held, "the tool's own record says held")
            assertNull(toolCompleted.errorMessage, "and names no error")
        }

    @Test
    fun `a held run stores no knowledge and writes no outcome row, and still brackets LEARN`() =
        runBlocking<Unit> {
            val host = seat(HOST_ID, HOST_ROLE) { planJson(sendStep()) }
            val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

            val run = openRun(host, doer, policy = RunPolicy(learn = LearnPolicy.ModelBacked))
            run.execute()
            val hostCallsBeforeLearn = host.transport.callCount
            val doerCallsBeforeLearn = doer.transport.callCount
            run.close()

            assertEquals(
                hostCallsBeforeLearn,
                host.transport.callCount,
                "ModelBacked makes no evaluation call for work that did not finish",
            )
            assertEquals(doerCallsBeforeLearn, doer.transport.callCount)
            assertTrue(
                door.database.knowledgeStoreQueries.findKnowledgeByRunId(run.runId).executeAsList().isEmpty(),
                "no seat stores Knowledge from a held run",
            )
            assertTrue(events(run.runId).none { it is MemoryEvent.KnowledgeStored })
            assertTrue(
                store.outcomes.getOutcomesByTicket(run.runId).getOrThrow().isEmpty(),
                "no outcome row: the store can only say succeeded or failed",
            )

            val entered = rows(run.runId).filter { it.source_id == HOST_ID }
                .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
                .count { it.newPhase == CognitivePhase.LEARN }
            val exited = rows(run.runId).filter { it.source_id == HOST_ID }
                .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseExited }
                .count { it.exitedPhase == CognitivePhase.LEARN }
            assertEquals(1, entered, "LEARN is still entered — entered and empty is not skipped")
            assertEquals(1, exited, "and exited")
        }

    @Test
    fun `TaskBlocked is published when the step is held, and closing publishes neither TaskCompleted nor TaskFailed`() =
        runBlocking<Unit> {
            val host = seat(HOST_ID, HOST_ROLE) { planJson(sendStep()) }
            val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

            val run = openRun(host, doer)
            val result = run.execute()

            val blocked = events(run.runId).filterIsInstance<TaskEvent.TaskBlocked>().single()
            assertEquals(run.runId, blocked.taskId, "the run's task is the one blocked")
            assertEquals(result.stepOutcomes.single().id, blocked.blockedByTaskId, "on the held step")
            assertEquals(REASON, blocked.reason)

            run.close()

            val afterClose = events(run.runId)
            assertTrue(afterClose.none { it is TaskEvent.TaskCompleted }, "a held run is not finished")
            assertTrue(afterClose.none { it is TaskEvent.TaskFailed }, "and did not fail")
        }

    @Test
    fun `no judgment is asked after a held cycle`() = runBlocking<Unit> {
        val judged = CopyOnWriteArrayList<String>()
        val judge: UpstreamDecisionClient = DeterministicDecisionClient(name = "test-judge") { state, _ ->
            judged += state
            Question.Noul.TRUE_KEY
        }
        val host = seat(HOST_ID, HOST_ROLE, decision = judge) { planJson(sendStep()) }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer, policy = RunPolicy(goalCheck = GoalCheck.Record))
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Held, result.status)
        assertTrue(judged.isEmpty(), "the judge was never called")
        assertTrue(
            rows(run.runId).none { it.event_type == CognitiveEvent.JudgmentRecorded.EVENT_TYPE },
            "and nothing was recorded",
        )
    }

    @Test
    fun `a hold in the second cycle outranks a failure in the first`() = runBlocking<Unit> {
        var planCalls = 0
        val host = seat(HOST_ID, HOST_ROLE) {
            if (planCalls++ == 0) planJson(flakyStep()) else planJson(sendStep())
        }
        val doer = seat(DOER_ID, DOER_ROLE) { "unused" }

        val run = openRun(host, doer, policy = RunPolicy(cycles = 3))
        val result = run.execute()
        run.close()

        assertIs<StepOutcome.Failure>(result.stepOutcomes[0], "the first cycle's step failed")
        assertIs<StepOutcome.Held>(result.stepOutcomes[1], "the second cycle's step held")
        assertEquals(RunStatus.Held, result.status, "the hold is why the run ended")
        assertEquals(2, result.cyclesUsed, "and no third cycle was planned")
        assertEquals(2, host.planCalls())
        assertIs<ExecutionOutcome.Held>(result.outcome)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private class Seat(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    ) {
        fun planCalls(): Int = transport.prompts.count { it.contains("\"steps\"") }
    }

    private fun seat(
        id: String,
        role: RoleId,
        decision: UpstreamDecisionClient? = null,
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
                upstreamDecisionClient = decision,
            ),
            transport = transport,
        )
    }

    private suspend fun openRun(
        host: Seat,
        doer: Seat,
        policy: RunPolicy = RunPolicy(),
    ): HostedRun = rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
        roster = RosterConfig(
            host = HOST_ROLE,
            roles = listOf(
                RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
                RoleConfig(
                    id = DOER_ROLE,
                    title = "Doer",
                    instructions = PROMPT,
                    tools = setOf(SEND_TOOL, SEARCH_TOOL, FLAKY_TOOL),
                ),
            ),
        ),
        seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer.descriptor),
        goal = goal(GOAL_TEXT, id = GOAL_ID),
        tools = setOf<Tool<*>>(
            holdingTool(SEND_TOOL),
            recordingTool(SEARCH_TOOL, "found it", dispatched),
            failingTool(FLAKY_TOOL, "the index is unreachable", dispatched),
        ),
        policy = policy,
    )

    /** A tool whose action waits on a person: it records its request and holds. */
    private fun holdingTool(id: String): FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
        id = id,
        name = "Holding $id",
        description = "acts on a person's behalf once they approve",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            heldDispatches += request
            val now = Clock.System.now()
            ExecutionOutcome.Held(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                reason = REASON,
                reference = REFERENCE,
            )
        },
    )

    private fun sendStep(): String = planStep("Send the reply", SEND_TOOL.seatId(), DOER_ROLE.value)

    private fun searchStep(): String = planStep("Search the archive", SEARCH_TOOL.seatId(), DOER_ROLE.value)

    private fun flakyStep(): String = planStep("Look up the thread", FLAKY_TOOL.seatId(), DOER_ROLE.value)

    private fun rows(runId: String): List<EventStore> =
        door.database.eventStoreQueries.getEventsByRunId(runId).executeAsList()

    private fun events(runId: String): List<Event> = rows(runId).map(::decode)

    private fun hostBrackets(runId: String): List<CognitivePhase> = rows(runId)
        .filter { it.source_id == HOST_ID }
        .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
        .map { it.newPhase }

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    /** The id this run offers the planner for a tool of the doer seat: `<seat>/<tool>`. */
    private fun String.seatId(): String = SeatTool(DOER_ROLE, this).id

    private companion object {
        const val SDK_ID = "hosted-run-held-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val SEND_TOOL = "send_reply"
        const val SEARCH_TOOL = "search"
        const val FLAKY_TOOL = "lookup"
        const val GOAL_TEXT = "Answer the thread"
        const val GOAL_ID = "goal-ampr-425"
        const val REASON = "waiting for the account owner to approve sending"
        const val REFERENCE = "approval-request-17"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
