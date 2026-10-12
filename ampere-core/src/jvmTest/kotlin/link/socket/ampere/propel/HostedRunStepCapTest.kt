package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.task.Task
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
 * AMPR-452 (AMPR-385 row H20 (c), as ruled on 2026-10-11): an optional step cap per cycle.
 *
 * Under `RunPolicy.maxStepsPerCycle` a plan that is longer than the cap, or that names a
 * tool no seat of the run has, goes back to the planner once. A second plan that still
 * breaks a rule is cut — unknown tools dropped, then the rest cut to the cap — and a
 * `PlanTruncated` is on the record before the gate is asked. With no cap nothing is
 * checked and nothing changes.
 *
 * Driven through the production wiring like [HostedRunDependentsTest]: real doors onto one
 * store, a real `FunctionExecutor`, real phase services behind counting fakes. The host's
 * answers are handed out one per PLAN call, so a test states the first plan and the
 * re-plan in order.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a
 * virtual clock walks straight past it.
 */
class HostedRunStepCapTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var store: MemoryStore

    /** Requests each tool was dispatched with, by bare tool id. */
    private val dispatched: Map<String, MutableList<ExecutionRequest<*>>> =
        listOf(SEARCH_TOOL, DRAFT_TOOL, SEND_TOOL)
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
    fun `a plan within the cap is unchanged and makes no extra PLAN call`() = runBlocking<Unit> {
        val host = host(planJson(step(SEARCH_STEP, SEARCH_TOOL), step(DRAFT_STEP, DRAFT_TOOL)))
        val gate = RecordingGate()

        val run = openRun(host, RunPolicy(maxStepsPerCycle = 2, planGate = gate))
        val result = run.execute()
        run.close()

        assertEquals(1, host.planCalls(), "a plan within bounds is not sent back")
        assertEquals(listOf(SEARCH_STEP, DRAFT_STEP), gate.reviewed.single().descriptions())
        assertEquals(2, result.stepOutcomes.size)
        assertTrue(result.stepOutcomes.all { it is StepOutcome.Success })
        assertTrue(events().none { it is PlanEvent.PlanTruncated })
    }

    @Test
    fun `an over-long plan triggers exactly one re-plan`() = runBlocking<Unit> {
        val host = host(
            planJson(step(SEARCH_STEP, SEARCH_TOOL), step(DRAFT_STEP, DRAFT_TOOL), step(SEND_STEP, SEND_TOOL)),
            planJson(step(SEARCH_STEP, SEARCH_TOOL), step(DRAFT_STEP, DRAFT_TOOL)),
        )
        val gate = RecordingGate()

        val run = openRun(host, RunPolicy(maxStepsPerCycle = 2, planGate = gate))
        val result = run.execute()
        run.close()

        assertEquals(2, host.planCalls(), "one re-plan, and no more")
        val replan = host.planPrompts()[1]
        assertTrue(
            replan.contains("It had 3 steps, and this run allows at most 2 steps per cycle"),
            "the re-plan is told what was wrong",
        )
        assertTrue(!host.planPrompts()[0].contains("Correction to the previous plan"))
        assertEquals(listOf(SEARCH_STEP, DRAFT_STEP), gate.reviewed.single().descriptions())
        assertEquals(2, result.stepOutcomes.size)
        assertEquals(0, dispatched.getValue(SEND_TOOL).size)
        assertTrue(events().none { it is PlanEvent.PlanTruncated }, "the re-plan fit, so nothing was cut")
    }

    @Test
    fun `a still over-long plan is cut and PlanTruncated is on the record before the gate`() = runBlocking<Unit> {
        val overLong = planJson(
            step(SEARCH_STEP, SEARCH_TOOL),
            step(DRAFT_STEP, DRAFT_TOOL),
            step(SEND_STEP, SEND_TOOL),
        )
        val host = host(overLong, overLong)
        val gate = RecordingGate { truncatedAtReview = events().filterIsInstance<PlanEvent.PlanTruncated>() }

        val run = openRun(host, RunPolicy(maxStepsPerCycle = 2, planGate = gate))
        val result = run.execute()
        run.close()

        assertEquals(2, host.planCalls())
        val truncated = gate.truncatedAtReview.single()
        assertEquals(RUN_ID, truncated.runId)
        assertEquals(1, truncated.cycle)
        assertEquals(3, truncated.originalStepCount)
        assertEquals(2, truncated.keptStepCount)
        assertEquals(2, truncated.maxStepsPerCycle)
        assertEquals(listOf("cut 1 step(s) over the cap of 2 per cycle"), truncated.reasons)
        assertEquals(HOST_ID, truncated.eventSource.getIdentifier(), "the cut is the run's, through the host's door")

        val reviewed = gate.reviewed.single()
        assertEquals(listOf(SEARCH_STEP, DRAFT_STEP), reviewed.descriptions(), "the first steps are kept")
        assertEquals(reviewed.id, truncated.planId, "the event names the plan that was kept")
        assertEquals(2, result.stepOutcomes.size)
        assertEquals(0, dispatched.getValue(SEND_TOOL).size, "the cut step never ran")
        assertEquals(RunStatus.Succeeded, result.status)
    }

    @Test
    fun `an unknown tool triggers one re-plan and is then dropped`() = runBlocking<Unit> {
        val withUnknown = planJson(step(SEARCH_STEP, SEARCH_TOOL), unknownStep())
        val host = host(withUnknown, withUnknown)
        val gate = RecordingGate()

        val run = openRun(host, RunPolicy(maxStepsPerCycle = 5, planGate = gate))
        val result = run.execute()
        run.close()

        assertEquals(2, host.planCalls())
        assertTrue(
            host.planPrompts()[1].contains("It named tools this run does not have: $UNKNOWN_TOOL."),
            "the re-plan is told which ids it got wrong",
        )
        assertTrue(!host.planPrompts()[1].contains("steps per cycle"), "the plan was within the cap")

        val truncated = events().filterIsInstance<PlanEvent.PlanTruncated>().single()
        assertEquals(2, truncated.originalStepCount)
        assertEquals(1, truncated.keptStepCount)
        assertEquals(listOf(UNKNOWN_TOOL), truncated.droppedToolIds)
        assertEquals(
            listOf("dropped 1 step(s) naming tools this run does not have: $UNKNOWN_TOOL"),
            truncated.reasons,
        )
        assertEquals(listOf(SEARCH_STEP), gate.reviewed.single().descriptions(), "the gate never saw it")
        assertIs<StepOutcome.Success>(result.stepOutcomes.single())
        assertEquals(RunStatus.Succeeded, result.status)
    }

    @Test
    fun `a plan cut to nothing skips the gate and fails the run`() = runBlocking<Unit> {
        val onlyUnknown = planJson(unknownStep())
        val host = host(onlyUnknown, onlyUnknown)
        val gate = RecordingGate()

        val run = openRun(host, RunPolicy(maxStepsPerCycle = 3, planGate = gate))
        val result = run.execute()
        run.close()

        assertEquals(2, host.planCalls())
        assertTrue(gate.reviewed.isEmpty(), "there was nothing left to approve")
        assertEquals(0, events().filterIsInstance<PlanEvent.PlanTruncated>().single().keptStepCount)
        assertTrue(result.stepOutcomes.isEmpty())
        assertEquals(RunStatus.Failed, result.status, "the run could not attempt what was asked")
        assertTrue(events().any { it is TaskEvent.TaskFailed })
    }

    @Test
    fun `with no cap nothing is checked and an unknown tool still fails at dispatch`() = runBlocking<Unit> {
        val host = host(planJson(step(SEARCH_STEP, SEARCH_TOOL), unknownStep()))
        val gate = RecordingGate()

        val run = openRun(host, RunPolicy(planGate = gate))
        val result = run.execute()
        run.close()

        assertNull(RunPolicy().maxStepsPerCycle, "the cap is off by default")
        assertEquals(1, host.planCalls(), "no cap, no re-plan")
        assertTrue(host.planPrompts().none { it.contains("Correction to the previous plan") })
        assertEquals(2, gate.reviewed.single().tasks.size, "the gate sees the plan as written")
        assertTrue(events().none { it is PlanEvent.PlanTruncated })
        assertIs<StepOutcome.Failure>(result.stepOutcomes[1])
        assertEquals(RunStatus.Partial, result.status)
    }

    @Test
    fun `a cap below one is refused`() {
        assertFailsWith<IllegalArgumentException> { RunPolicy(maxStepsPerCycle = 0) }
        assertEquals(1, RunPolicy(maxStepsPerCycle = 1).maxStepsPerCycle)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /** The host seat, answering PLAN with [plans] in order and repeating the last. */
    private class Host(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    ) {
        fun planPrompts(): List<String> = transport.prompts.filter { it.contains("planning module") }
        fun planCalls(): Int = planPrompts().size
    }

    private fun host(vararg plans: String): Host {
        val next = AtomicInteger(0)
        val transport = CountingUpstreamLlmClient(
            hostAnswers { plans[minOf(next.getAndIncrement(), plans.lastIndex)] },
        )
        return Host(
            descriptor = HostedAgent(
                id = HOST_ID,
                role = HOST_ROLE,
                charter = "You are the ${HOST_ROLE.value} of this run.",
                aiConfiguration = AI_CONFIGURATION,
                upstreamLlmClient = transport,
                memory = store,
            ),
            transport = transport,
        )
    }

    /** A gate that approves everything and remembers what it was shown. */
    private class RecordingGate(
        private val onReview: suspend RecordingGate.(Plan) -> Unit = {},
    ) : PlanGate {
        val reviewed: MutableList<Plan> = CopyOnWriteArrayList()
        var truncatedAtReview: List<PlanEvent.PlanTruncated> = emptyList()

        override suspend fun review(plan: Plan): PlanDecision {
            reviewed += plan
            onReview(this, plan)
            return PlanDecision.Approved(plan)
        }
    }

    private suspend fun openRun(host: Host, policy: RunPolicy): HostedRun {
        val doer = HostedAgent(
            id = DOER_ID,
            role = DOER_ROLE,
            charter = "You are the ${DOER_ROLE.value} of this run.",
            aiConfiguration = AI_CONFIGURATION,
            upstreamLlmClient = CountingUpstreamLlmClient(fixedAnswer { "unused" }),
            memory = store,
        )
        return rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
            roster = RosterConfig(
                host = HOST_ROLE,
                roles = listOf(
                    RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
                    RoleConfig(
                        id = DOER_ROLE,
                        title = "Doer",
                        instructions = PROMPT,
                        tools = setOf(SEARCH_TOOL, DRAFT_TOOL, SEND_TOOL),
                    ),
                ),
            ),
            seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer),
            goal = goal(GOAL_TEXT, id = GOAL_ID),
            tools = setOf<Tool<*>>(
                recordingTool(SEARCH_TOOL, "found it", dispatched.getValue(SEARCH_TOOL)),
                recordingTool(DRAFT_TOOL, "drafted", dispatched.getValue(DRAFT_TOOL)),
                recordingTool(SEND_TOOL, "sent", dispatched.getValue(SEND_TOOL)),
            ),
            policy = policy,
            runId = RUN_ID,
        )
    }

    /** One plan step on the doer seat, naming the seat-namespaced id the run offered. */
    private fun step(description: String, tool: String): String =
        planStep(description = description, toolToUse = SeatTool(DOER_ROLE, tool).id, seat = DOER_ROLE.value)

    /** A step naming a tool no seat of the run has. */
    private fun unknownStep(): String =
        planStep(description = UNKNOWN_STEP, toolToUse = UNKNOWN_TOOL, seat = DOER_ROLE.value)

    private fun Plan.descriptions(): List<String> = tasks.map { assertIs<Task.Step>(it).description }

    private fun events(): List<Event> =
        door.database.eventStoreQueries.getEventsByRunId(RUN_ID).executeAsList().map(::decode)

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    private companion object {
        const val SDK_ID = "hosted-run-step-cap-test-sdk"
        const val RUN_ID = "run-ampr-452"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val SEARCH_TOOL = "search"
        const val DRAFT_TOOL = "draft_reply"
        const val SEND_TOOL = "send_reply"
        const val UNKNOWN_TOOL = "teleport"
        const val SEARCH_STEP = "Search the archive"
        const val DRAFT_STEP = "Draft the reply"
        const val SEND_STEP = "Send the reply"
        const val UNKNOWN_STEP = "Teleport the reply"
        const val GOAL_TEXT = "Answer the thread"
        const val GOAL_ID = "goal-ampr-452"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
