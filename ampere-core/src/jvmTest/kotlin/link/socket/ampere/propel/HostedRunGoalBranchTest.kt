package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.GoalEvent
import link.socket.ampere.agents.domain.event.SubjectEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.field.Ref
import link.socket.ampere.goal.GoalBranch
import link.socket.ampere.goal.GoalRecord
import link.socket.ampere.goal.GoalStatus
import link.socket.ampere.goal.SqlDelightGoalStore
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool
import link.socket.ampere.subject.SqlDelightSubjectStore
import link.socket.ampere.subject.SubjectKind

/**
 * AMPR-473 (F34): a hosted run lives on the Goal branch.
 *
 * Opened with a [GoalBranch], a run forms one root goal before PERCEIVE — `<run id>/goal-0`,
 * held by the host seat — and registers every seat as a Subject; LEARN completes the goal for a
 * run that succeeded and abandons it for every other end but a hold. Opened without one, the run
 * writes nothing to the branch and publishes no goal or subject event.
 *
 * Driven through the production wiring — `rosterRunHost` over real doors onto one store, a real
 * `FunctionExecutor`, the real phase services behind counting fakes — like [HostedRunResumeTest].
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual clock
 * walks straight past it.
 */
class HostedRunGoalBranchTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var heldStore: HeldRunStore

    private val searched = CopyOnWriteArrayList<ExecutionRequest<*>>()
    private val broken = CopyOnWriteArrayList<ExecutionRequest<*>>()
    private val sent = CopyOnWriteArrayList<ExecutionRequest<*>>()

    /** Completed once [blockingTool] has been dispatched, so the test knows when to cancel. */
    private val stepReached = CompletableDeferred<Unit>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
        heldStore = InMemoryHeldRunStore()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    // ========================================================================
    // Forming on open
    // ========================================================================

    @Test
    fun `opening a run forms one open goal held by the host and registers every seat`() = runBlocking<Unit> {
        val run = openRun(hostSeat { planJson(searchStep()) })

        val record = goals(run.runId).single()
        assertEquals("${run.runId}/goal-0", record.id.value)
        assertEquals(GOAL_TEXT, record.goal.description)
        assertNull(record.goal.parent, "the run plans steps, not sub-goals: its goal is only a root")
        assertEquals(GoalStatus.OPEN, record.status)
        assertEquals(Ref(HOST_ID), record.subject, "the host seat holds the run's goal")
        assertEquals(run.runId, record.runId)

        val agents = SqlDelightSubjectStore(door.database).list(SubjectKind.AGENT).getOrThrow().map { it.id.value }
        assertTrue(
            agents.containsAll(listOf(HOST_ID, SCOUT_ID, CLERK_ID)),
            "every seat is a registered Subject; registered: $agents",
        )

        val formed = events(run.runId).filterIsInstance<GoalEvent.GoalFormed>()
        assertEquals(listOf(record.id.value), formed.map { it.goalId }, "one GoalFormed, under the run")
        assertEquals(record.goal.provenance.createdBy, formed.single().eventId)

        run.close()
    }

    // ========================================================================
    // Closing with the run
    // ========================================================================

    @Test
    fun `a run that succeeds completes its goal`() = runBlocking<Unit> {
        val run = openRun(hostSeat { planJson(searchStep()) })
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(listOf(GoalStatus.COMPLETED), goals(run.runId).map { it.status })
        val updated = events(run.runId).filterIsInstance<GoalEvent.GoalUpdated>().single()
        assertEquals(GoalStatus.COMPLETED.name, updated.status)
    }

    @Test
    fun `a partial run abandons its goal`() = runBlocking<Unit> {
        val run = openRun(hostSeat { planJson(searchStep(), brokenStep()) })
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Partial, result.status)
        assertEquals(listOf(GoalStatus.ABANDONED), goals(run.runId).map { it.status })
    }

    @Test
    fun `a failed run abandons its goal`() = runBlocking<Unit> {
        val run = openRun(hostSeat { planJson(brokenStep()) })
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Failed, result.status)
        assertEquals(listOf(GoalStatus.ABANDONED), goals(run.runId).map { it.status })
    }

    @Test
    fun `a stopped run abandons its goal`() = runBlocking<Unit> {
        val run = openRun(hostSeat { planJson(searchStep()) }, policy = RunPolicy(planGate = StopAlways))
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Stopped, result.status)
        assertEquals(listOf(GoalStatus.ABANDONED), goals(run.runId).map { it.status })
    }

    @Test
    fun `a cancelled run abandons its goal`() = runBlocking<Unit> {
        val run = openRun(hostSeat { planJson(blockingStep()) })
        val job = scope.launch { runCatching { run.execute() } }
        stepReached.await()
        job.cancelAndJoin()
        run.close()

        assertEquals(listOf(GoalStatus.ABANDONED), goals(run.runId).map { it.status })
    }

    @Test
    fun `a held run leaves its goal open, and its resume closes the one goal it formed`() = runBlocking<Unit> {
        val host = hostSeat { planJson(searchStep(), sendStep()) }
        val runHost = runHost()

        val held = openRun(host, runHost = runHost)
        assertEquals(RunStatus.Held, held.execute().status)
        held.close()
        assertEquals(listOf(GoalStatus.OPEN), goals(held.runId).map { it.status }, "a held run is waiting")

        val resumed = runHost.resume(
            runId = held.runId,
            roster = roster(),
            seats = seats(host),
            resolution = HoldResolution.Completed(HELD_RESULT),
            tools = tools(),
            policy = RunPolicy(),
        )
        assertEquals(RunStatus.Succeeded, resumed.execute().status)
        resumed.close()

        assertEquals(listOf(GoalStatus.COMPLETED), goals(held.runId).map { it.status }, "one goal, not two")
        val goalEvents = events(held.runId).filterIsInstance<GoalEvent>()
        assertEquals(1, goalEvents.count { it is GoalEvent.GoalFormed }, "the resume formed nothing again")
        assertEquals(1, goalEvents.count { it is GoalEvent.GoalUpdated })
        assertEquals(1, sent.size, "the held tool ran once")
    }

    // ========================================================================
    // Without a branch
    // ========================================================================

    @Test
    fun `a run opened without a branch writes no goal and registers no seat`() = runBlocking<Unit> {
        val run = openRun(hostSeat { planJson(searchStep()) }, runHost = runHost(goalBranch = false))
        assertEquals(RunStatus.Succeeded, run.execute().status)
        run.close()

        assertTrue(goals(run.runId).isEmpty())
        val agents = SqlDelightSubjectStore(door.database).list(SubjectKind.AGENT).getOrThrow().map { it.id.value }
        assertTrue(agents.none { it in setOf(HOST_ID, SCOUT_ID, CLERK_ID) }, "no seat registered: $agents")
        val everything = door.database.eventStoreQueries.getEventsSinceSequence(0).executeAsList().map(::decode)
        assertTrue(everything.none { it is GoalEvent || it is SubjectEvent }, "no branch event at all")
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private val searchTool by lazy { recordingTool(SEARCH_TOOL, SEARCH_RESULT, searched) }
    private val brokenTool by lazy { failingTool(BROKEN_TOOL, "the index is unreachable", broken) }
    private val sendTool by lazy { holdingTool(SEND_TOOL, sent) }

    /** A tool that reports it was reached and then waits to be cancelled. */
    private val blockingTool by lazy {
        FunctionTool<ExecutionContext.NoChanges>(
            id = BLOCKING_TOOL,
            name = "Blocking tool",
            description = "waits until the run is cancelled",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = {
                stepReached.complete(Unit)
                awaitCancellation()
            },
        )
    }

    private fun tools(): Set<Tool<*>> = setOf(searchTool, brokenTool, sendTool, blockingTool)

    private fun hostSeat(answering: () -> String): HostedAgent = seat(HOST_ID, HOST_ROLE, hostAnswers(answering))

    private fun seat(
        id: String,
        role: RoleId,
        answer: (String) -> String = fixedAnswer { "unused" },
    ): HostedAgent = HostedAgent(
        id = id,
        role = role,
        charter = "You are the ${role.value} of this run.",
        aiConfiguration = AI_CONFIGURATION,
        upstreamLlmClient = CountingUpstreamLlmClient(answer),
    )

    private fun seats(host: HostedAgent): Map<RoleId, HostedAgent> = mapOf(
        HOST_ROLE to host,
        SCOUT_ROLE to seat(SCOUT_ID, SCOUT_ROLE),
        CLERK_ROLE to seat(CLERK_ID, CLERK_ROLE),
    )

    private fun roster(): Roster = RosterConfig(
        host = HOST_ROLE,
        roles = listOf(
            RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
            RoleConfig(
                id = SCOUT_ROLE,
                title = "Scout",
                instructions = PROMPT,
                tools = setOf(SEARCH_TOOL, BROKEN_TOOL, BLOCKING_TOOL),
            ),
            RoleConfig(id = CLERK_ROLE, title = "Clerk", instructions = PROMPT, tools = setOf(SEND_TOOL)),
        ),
    )

    private fun runHost(goalBranch: Boolean = true): RunHost = rosterRunHost(
        createEventApi = door::doorFor,
        agentScope = scope,
        heldRuns = heldStore,
        goalBranch = branchOverSeatDoor.takeIf { goalBranch },
    )

    /** The production branch, over the test store: what `fromEnvironment` wires over a database. */
    private val branchOverSeatDoor: (AgentEventApi) -> GoalBranch = { seatDoor ->
        GoalBranch.over(door.database, seatDoor)
    }

    private suspend fun openRun(
        host: HostedAgent,
        runHost: RunHost = runHost(),
        policy: RunPolicy = RunPolicy(),
    ): HostedRun = runHost.open(
        roster = roster(),
        seats = seats(host),
        goal = goal(GOAL_TEXT, id = GOAL_ID),
        tools = tools(),
        policy = policy,
    )

    /** A tool whose action waits on a person: it records its request and holds. */
    private fun holdingTool(
        id: String,
        dispatched: MutableList<ExecutionRequest<*>>,
    ): FunctionTool<ExecutionContext.NoChanges> = FunctionTool(
        id = id,
        name = "Holding $id",
        description = "acts on a person's behalf once they approve",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            dispatched += request
            val now = Clock.System.now()
            ExecutionOutcome.Held(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                reason = "waiting for the account owner to approve sending",
                reference = null,
            )
        },
    )

    private fun searchStep(): String = planStep("Search the archive", scoutTool(SEARCH_TOOL), SCOUT_ROLE.value)

    private fun brokenStep(): String = planStep("Query the index", scoutTool(BROKEN_TOOL), SCOUT_ROLE.value)

    private fun blockingStep(): String = planStep("Wait on the index", scoutTool(BLOCKING_TOOL), SCOUT_ROLE.value)

    private fun sendStep(): String =
        planStep("Send the reply", SeatTool(CLERK_ROLE, SEND_TOOL).id, CLERK_ROLE.value)

    private fun scoutTool(tool: String): String = SeatTool(SCOUT_ROLE, tool).id

    private suspend fun goals(runId: String): List<GoalRecord> =
        SqlDelightGoalStore(door.database).forRun(runId).getOrThrow()

    private fun events(runId: String): List<Event> =
        door.database.eventStoreQueries.getEventsByRunId(runId).executeAsList().map(::decode)

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    private companion object {
        const val SDK_ID = "hosted-run-goal-branch-test-sdk"
        const val HOST_ID = "seat-host"
        const val SCOUT_ID = "seat-scout"
        const val CLERK_ID = "seat-clerk"
        const val SEARCH_TOOL = "search"
        const val BROKEN_TOOL = "query_index"
        const val BLOCKING_TOOL = "wait_on_index"
        const val SEND_TOOL = "send_reply"
        const val SEARCH_RESULT = "found three earlier messages"
        const val HELD_RESULT = "sent at 09:14, message id m-881"
        const val GOAL_TEXT = "Answer the thread"
        const val GOAL_ID = "goal-ampr-473"

        val HOST_ROLE = RoleId("planner")
        val SCOUT_ROLE = RoleId("scout")
        val CLERK_ROLE = RoleId("clerk")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )

        /** A gate that stops every run. */
        val StopAlways = object : PlanGate {
            override suspend fun review(plan: Plan): PlanDecision = PlanDecision.Stopped("a person said no")
        }
    }
}
