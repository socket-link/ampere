package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.SparkAppliedEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.AssignedTo
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
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool
import link.socket.ampere.trace.ArcTraceProjection

/**
 * Task 1 of AMPR-393: a two-seat roster-hosted run, end to end.
 *
 * Driven through the production wiring — real doors onto one store, a real
 * `FunctionExecutor`, real phase services behind a counting fake transport — so what
 * is asserted here is what a consumer's run records and what it is billed for.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a
 * virtual clock walks straight past it (and the agents' observability scope is a real
 * one).
 */
class HostedRunTest {

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
        // Cancel before closing: a seat's spark snapshot still in flight would
        // otherwise write to a closed driver.
        scope.cancel()
        door.close()
    }

    @Test
    fun `a two-seat run records all six phases, the run id, and the seat that did each step`() =
        runBlocking<Unit> {
            val host = seat(HOST_ID, HOST_ROLE) {
                planJson(
                    planStep("Search for the retry policy", DOER_TOOL.seatId(), DOER_ROLE.value),
                    planStep("Decide what to change", null, HOST_ROLE.value),
                )
            }
            val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

            val run = openRun(host, doer)
            assertEquals(2, run.plan.tasks.size, "the gated plan is the one the host produced")

            val result = run.execute()
            run.close()

            assertEquals(RunStatus.Succeeded, result.status, "both steps did their work")
            assertEquals(1, result.cyclesUsed, "the default policy is one cycle")
            assertEquals(2, result.stepOutcomes.size)

            // --- every envelope carries the run, and names a seat of it -------------
            val rows = door.database.eventStoreQueries.getEventsByRunId(run.runId).executeAsList()
            assertTrue(rows.isNotEmpty(), "the run published something")
            assertTrue(
                rows.all { it.run_id == run.runId },
                "the `run_id` query is the run, by construction",
            )
            val sources = rows.mapTo(mutableSetOf()) { it.source_id }
            assertEquals(
                setOf(HOST_ID, DOER_ID),
                sources,
                "every envelope names a seat of this run, and nothing else — not the SDK's " +
                    "own door; got $sources",
            )

            // --- the phases, bracketed, on the host's door --------------------------
            val hostBrackets = rows
                .filter { it.source_id == HOST_ID }
                .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
                .map { it.newPhase }
            assertTrue(
                enumValues<CognitivePhase>().all { it in hostBrackets },
                "the host seat enters all six phases; got $hostBrackets",
            )

            // --- the work, on the seat that did it ----------------------------------
            val toolPairs = rows
                .mapNotNull { decode(it) as? ToolEvent.ToolExecutionStarted }
            assertEquals(1, toolPairs.size, "one tool ran")
            assertEquals(
                DOER_ID,
                toolPairs.single().eventSource.getIdentifier(),
                "the tool step's tool events leave through the seat that ran it",
            )

            val steps = rows.mapNotNull { decode(it) as? PlanEvent.PlanStepStarted }
            assertEquals(2, steps.size, "one started pair per step")
            val toolStep = assertNotNull(steps.firstOrNull { it.stepIndex == 0 })
            assertEquals(
                DOER_ID,
                toolStep.eventSource.getIdentifier(),
                "a step's pair leaves through the seat it ran on, not the walker's door",
            )
            assertEquals(
                AssignedTo.Agent(DOER_ID),
                toolStep.assignedTo,
                "and carries the seat it was assigned to (H5)",
            )
            assertEquals(
                HOST_ID,
                assertNotNull(steps.firstOrNull { it.stepIndex == 1 }).eventSource.getIdentifier(),
                "the reasoning step ran on the host seat, which the plan assigned it to",
            )
            assertTrue(
                dispatched.size == 1 && dispatched.single().runId == run.runId,
                "the tool was dispatched once, under the run; got ${dispatched.size}",
            )

            // --- LEARN, for both seats ----------------------------------------------
            val stored = rows
                .mapNotNull { decode(it) as? MemoryEvent.KnowledgeStored }
            assertEquals(
                setOf(HOST_ID, DOER_ID),
                stored.mapTo(mutableSetOf()) { it.eventSource.getIdentifier() },
                "every seat that executed a step stores what it learned, through its own door",
            )
            assertTrue(
                stored.all { it.runId == run.runId },
                "and stores it under the run, not under a task id",
            )
            val outcomes = store.outcomes.getOutcomesByTicket(run.runId).getOrThrow()
            assertEquals(1, outcomes.size, "one recordOutcome for the run, keyed by the run id")
            assertEquals(HOST_ID, outcomes.single().executorId, "attributed to the host seat")

            val completed = rows.mapNotNull { decode(it) as? TaskEvent.TaskCompleted }
            assertEquals(1, completed.size, "the run's task ends exactly once")
            assertEquals(run.runId, completed.single().taskId, "and its id is the run's")

            // --- the sparks that differentiated the seats ---------------------------
            assertTrue(
                rows.any { decode(it) is SparkAppliedEvent },
                "the seats' charters are on the record as applied sparks",
            )

            // --- and the trace reads all of it back ---------------------------------
            val trace = ArcTraceProjection(door.database)
                .project(runId = run.runId, arcId = ARC_ID)
                .getOrThrow()
            val phaseNames = trace.phases.map { it.name }
            enumValues<CognitivePhase>().forEach { phase ->
                assertTrue(
                    phase.name in phaseNames,
                    "ArcTraceProjection returns ${phase.name}; got $phaseNames",
                )
            }
        }

    @Test
    fun `the plan gate can trim a plan, and only the kept steps run`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(
                planStep("Search once", DOER_TOOL.seatId(), DOER_ROLE.value),
                planStep("Search again", DOER_TOOL.seatId(), DOER_ROLE.value),
            )
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer, policy = RunPolicy(planGate = TrimToFirst))
        assertEquals(1, run.plan.tasks.size, "`plan` is what the gate approved, not what was asked")

        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(1, dispatched.size, "the trimmed step never ran")
    }

    @Test
    fun `a stopped plan runs nothing, still closes, and records its outcome`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(planStep("Search once", DOER_TOOL.seatId(), DOER_ROLE.value))
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer, policy = RunPolicy(planGate = StopAlways))
        assertEquals(Plan.Blank, run.plan, "a stopped run has nothing about to happen")

        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Stopped, result.status)
        assertTrue(result.stepOutcomes.isEmpty(), "no step ran")
        assertEquals(0, dispatched.size)
        assertEquals(
            1,
            store.outcomes.getOutcomesByTicket(run.runId).getOrThrow().size,
            "a stopped run still records its outcome",
        )
        assertTrue(
            knowledgeRows(run.runId).isEmpty(),
            "and stores no Knowledge, because no seat executed anything",
        )
        val rows = door.database.eventStoreQueries.getEventsByRunId(run.runId).executeAsList()
        assertTrue(
            rows.any { decode(it) is Event.TaskCreated },
            "the plan's TaskCreated goes out before the gate, so a stopped plan is on the record",
        )
        assertTrue(
            rows.any { decode(it) is TaskEvent.TaskCompleted },
            "a run that was told to stop and stopped completed",
        )
    }

    /**
     * A plan with no steps ends the run after one cycle, whatever the cap.
     *
     * Reached through a gate that kept nothing rather than through the planner:
     * `PlanGenerator` turns an empty `steps` array into a one-step *fallback* plan
     * ("Advanced planning unavailable"), so with a transport wired — which a
     * [HostedAgent] requires — a plan with no steps is what the gate left, or
     * `Plan.Blank`. Either way the run has nothing to ask for and stops.
     */
    @Test
    fun `a plan with no steps ends the run after one cycle, whatever the cap`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(planStep("Search", DOER_TOOL.seatId(), DOER_ROLE.value))
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer, policy = RunPolicy(cycles = 3, planGate = TrimToNothing))
        val result = run.execute()
        run.close()

        assertEquals(1, result.cyclesUsed, "the cap was 3; there was nothing left to run")
        assertEquals(1, host.planCalls(), "and only one PLAN call was billed")
        assertEquals(0, dispatched.size)
    }

    @Test
    fun `a cap of two never runs a third PLAN`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(planStep("Search", DOER_TOOL.seatId(), DOER_ROLE.value))
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer, policy = RunPolicy(cycles = 2))
        val result = run.execute()
        run.close()

        assertEquals(2, result.cyclesUsed)
        assertEquals(2, host.planCalls(), "two cycles, two PLAN calls, and no third")
        assertEquals(2, dispatched.size, "each cycle executed its own plan")
        assertEquals(2, result.stepOutcomes.size, "and every cycle's steps are reported")
    }

    @Test
    fun `the deterministic LEARN makes no model call`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(planStep("Search once", DOER_TOOL.seatId(), DOER_ROLE.value))
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer)
        run.execute()
        val hostCallsBeforeLearn = host.transport.callCount
        val doerCallsBeforeLearn = doer.transport.callCount
        run.close()

        assertEquals(
            hostCallsBeforeLearn,
            host.transport.callCount,
            "LearnPolicy.Deterministic bills nothing: extractDefault is not a prompt",
        )
        assertEquals(doerCallsBeforeLearn, doer.transport.callCount)
        assertTrue(
            knowledgeRows(run.runId).isNotEmpty(),
            "and it still closes the loop — Knowledge without a model call is the point",
        )
    }

    /**
     * Cancelled the way a run actually is: the scope driving it is cancelled while a step
     * is in flight.
     *
     * Not by throwing from inside the tool — `FunctionExecutor` and `ToolExecutionEngine`
     * both `catch (e: Exception)`, and a `CancellationException` is an `Exception`, so a
     * tool that cancels itself comes back as a failed step on a live run. The blocking
     * tool below suspends until the run is cancelled from outside, which is the shape a
     * consumer's cancelled run has.
     */
    @Test
    fun `a cancelled run records a failed outcome and stores no knowledge`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) {
            planJson(planStep("Search once", BLOCKING_TOOL.seatId(), DOER_ROLE.value))
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer, tools = setOf(blockingTool))
        var thrown: Throwable? = null
        val job = scope.launch { thrown = runCatching { run.execute() }.exceptionOrNull() }
        stepReached.await()
        job.cancelAndJoin()
        run.close()

        assertTrue(
            thrown is CancellationException,
            "execute rethrows the cancellation; got ${thrown?.let { it::class.simpleName }}",
        )
        val recorded = store.outcomes.getOutcomesByTicket(run.runId).getOrThrow()
        assertEquals(1, recorded.size, "a cancelled run records its outcome under NonCancellable")
        assertTrue(
            !recorded.single().success,
            "and records it as a failure, which is what a run that did not finish is",
        )
        assertTrue(
            knowledgeRows(run.runId).isEmpty(),
            "a cancelled run learned nothing it can claim to have learned",
        )

        val rows = door.database.eventStoreQueries.getEventsByRunId(run.runId).executeAsList()
        assertTrue(
            rows.any { decode(it) is PlanEvent.PlanStepCompleted },
            "the cancelled step published its completed pair before the run rethrew",
        )
        assertTrue(
            rows.any { decode(it) is TaskEvent.TaskFailed },
            "and the run's task ended failed",
        )
    }

    @Test
    fun `an open run reports its seats, and a closed one stops`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) { planJson() }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }
        val runHost = rosterRunHost(createEventApi = door::doorFor, agentScope = scope)

        val run = runHost.open(
            roster = roster(),
            seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer.descriptor),
            goal = goal(GOAL_TEXT),
            tools = setOf(searchTool),
        )

        val seats = runHost.openRunSeats()
        assertEquals(setOf(HOST_ID, DOER_ID), seats.mapTo(mutableSetOf()) { it.agentId })
        assertEquals(
            HOST_ROLE,
            assertNotNull(seats.firstOrNull { it.isHost }).role,
            "exactly the roster's host reports as the host",
        )
        assertTrue(seats.all { it.runId == run.runId && it.goal == GOAL_TEXT })

        run.execute()
        run.close()
        assertTrue(runHost.openRunSeats().isEmpty(), "a closed run is not an open run")
    }

    @Test
    fun `open refuses a roster whose host nobody fills`() = runBlocking<Unit> {
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }
        val runHost = rosterRunHost(createEventApi = door::doorFor, agentScope = scope)

        val failure = runCatching {
            runHost.open(
                roster = roster(),
                seats = mapOf(DOER_ROLE to doer.descriptor),
                goal = goal(GOAL_TEXT),
            )
        }.exceptionOrNull()

        assertTrue(
            failure is IllegalArgumentException &&
                failure.message.orEmpty().contains(HOST_ROLE.value),
            "a run with nobody to plan is not a run; got $failure",
        )
        assertTrue(runHost.openRunSeats().isEmpty(), "and nothing was left registered")
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private class Seat(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    ) {
        /** How many PLAN calls this seat was billed for, told by the prompt's own schema. */
        fun planCalls(): Int = transport.prompts.count { it.contains("\"steps\"") }
    }

    private fun seat(
        id: String,
        role: RoleId,
        answering: () -> String,
    ): Seat {
        val transport = CountingUpstreamLlmClient(answersFor(role, answering))
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

    /**
     * How a seat answers. The host has to tell PERCEIVE's question from PLAN's; every
     * other seat answers the same text, because its only work here is a reasoning step.
     *
     * A named function rather than an `if` with a lambda in its `else`: `else { _ -> … }`
     * parses as a block.
     */
    private fun answersFor(role: RoleId, answering: () -> String): (String) -> String =
        if (role == HOST_ROLE) hostAnswers(answering) else fixedAnswer(answering)

    private fun fixedAnswer(answering: () -> String): (String) -> String = { answering() }

    private suspend fun openRun(
        host: Seat,
        doer: Seat,
        policy: RunPolicy = RunPolicy(),
        tools: Set<Tool<*>> = setOf(searchTool),
    ): HostedRun = rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
        roster = roster(),
        seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer.descriptor),
        goal = goal(GOAL_TEXT),
        tools = tools,
        policy = policy,
    )

    private fun roster(): RosterConfig = RosterConfig(
        host = HOST_ROLE,
        roles = listOf(
            RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
            RoleConfig(
                id = DOER_ROLE,
                title = "Doer",
                instructions = PROMPT,
                tools = setOf(DOER_TOOL, BLOCKING_TOOL),
            ),
        ),
    )

    private val searchTool = recordingTool(DOER_TOOL, TOOL_RESULT, dispatched)

    /** Completed once [blockingTool] has been dispatched, so the test knows when to cancel. */
    private val stepReached = CompletableDeferred<Unit>()

    /** A tool that reports it was reached and then waits to be cancelled. */
    private val blockingTool = FunctionTool<ExecutionContext.NoChanges>(
        id = BLOCKING_TOOL,
        name = "Blocking tool",
        description = "waits until the run is cancelled",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = {
            stepReached.complete(Unit)
            awaitCancellation()
        },
    )

    private fun knowledgeRows(runId: String) =
        door.database.knowledgeStoreQueries.findKnowledgeByRunId(runId).executeAsList()

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    /** The id this run offers the planner for a tool of the doer seat: `<seat>/<tool>`. */
    private fun String.seatId(): String = SeatTool(DOER_ROLE, this).id

    private companion object {
        const val SDK_ID = "hosted-run-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val DOER_TOOL = "search"
        const val BLOCKING_TOOL = "wait_to_be_cancelled"
        const val TOOL_RESULT = "found the retry policy in Uploader.kt"
        const val GOAL_TEXT = "Add a retry to the uploader"
        const val ARC_ID = "arc-ampr-393"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )

        /** A gate that keeps the first step and drops the rest. */
        val TrimToFirst = object : PlanGate {
            override suspend fun review(plan: Plan): PlanDecision = PlanDecision.Approved(
                (plan as Plan.ForTask).copy(tasks = plan.tasks.take(1)),
            )
        }

        /** A gate that approves a plan with every step removed. */
        val TrimToNothing = object : PlanGate {
            override suspend fun review(plan: Plan): PlanDecision = PlanDecision.Approved(
                (plan as Plan.ForTask).copy(tasks = emptyList()),
            )
        }

        /** A gate that stops every run. */
        val StopAlways = object : PlanGate {
            override suspend fun review(plan: Plan): PlanDecision =
                PlanDecision.Stopped("a person said no")
        }
    }
}
