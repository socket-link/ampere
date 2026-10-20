package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.api.AmpereExperimentalApi
import link.socket.ampere.api.service.FieldService
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.field.Field
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.goal.GoalBranch
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-480 (N1, N10): a hosted run observes the Field.
 *
 * Driven through the production wiring — `rosterRunHost` over real doors onto one in-memory
 * database, the real `FieldService` over the same database, the real phase services behind
 * counting fakes — like [HostedRunGoalBranchTest]. Three claims:
 *
 * - `Off`, and a host with no Field, are byte-identical to before: the host seat's phase brackets
 *   and the run's own events are the pinned sequence, the PERCEIVE prompt carries no "# World",
 *   and nothing of the Field is kept.
 * - `Goal` opens exactly one OBSERVE bracket after each executed cycle — the same bracket a
 *   question would be asked in, never a second — and adds no event type.
 * - `Goal` renders the goal-scoped section into the host seat's PERCEIVE prompt, and a
 *   `PROTECTED` row the host holds is counted and never rendered.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual clock
 * walks straight past it.
 */
@OptIn(AmpereExperimentalApi::class)
class RosterHostedRunFieldTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var knowledge: KnowledgeRepositoryImpl
    private lateinit var fieldService: FieldService

    private val searched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
        knowledge = KnowledgeRepositoryImpl(door.database, door.driver)
        fieldService = FieldService.over(door.database, knowledge, door.repository)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    // ========================================================================
    // Off is byte-identical
    // ========================================================================

    @Test
    fun `Off on a host with a Field is the pinned two-cycle run and keeps no snapshot`() = runBlocking<Unit> {
        val host = hostSeat()
        val run = openRun(host, runHost = runHost(field = fieldService), policy = RunPolicy(cycles = 2))
        val result = run.execute()
        run.close()

        assertEquals(2, result.cyclesUsed)
        assertEquals(PINNED_TWO_CYCLE_HOST_PHASES, hostPhasesEntered(run.runId))
        assertEquals(PINNED_TWO_CYCLE_RUN_EVENTS, runEvents(run.runId))
        assertFalse(host.perceivePrompt().contains(WORLD_HEADING), "no section under Off: ${host.perceivePrompt()}")
        // `readExternalContent` is up here as it was before: the second PLAN was shown the search
        // tool's `External` result (AMPR-453). The single-cycle tests below are where the Field's
        // own effect on it shows.
        assertTrue(result.readExternalContent)
        val (perceived, observed) = assertIs<RosterHostedRun>(run).fieldSnapshots
        assertNull(perceived)
        assertNull(observed)
    }

    @Test
    fun `a Goal policy on a host with no Field is the same pinned run`() = runBlocking<Unit> {
        val host = hostSeat()
        val run = openRun(host, runHost = runHost(field = null), policy = GOAL_POLICY.copy(cycles = 2))
        run.execute()
        run.close()

        assertEquals(PINNED_TWO_CYCLE_HOST_PHASES, hostPhasesEntered(run.runId))
        assertEquals(PINNED_TWO_CYCLE_RUN_EVENTS, runEvents(run.runId))
        assertFalse(host.perceivePrompt().contains(WORLD_HEADING), "no Field to read: ${host.perceivePrompt()}")
        val (perceived, observed) = assertIs<RosterHostedRun>(run).fieldSnapshots
        assertNull(perceived)
        assertNull(observed)
    }

    // ========================================================================
    // Goal observes
    // ========================================================================

    @Test
    fun `Goal enters OBSERVE once after each executed cycle and adds no event type`() = runBlocking<Unit> {
        val host = hostSeat()
        val run = openRun(host, runHost = runHost(field = fieldService), policy = GOAL_POLICY.copy(cycles = 2))
        val result = run.execute()
        run.close()

        assertEquals(2, result.cyclesUsed)
        val phases = hostPhasesEntered(run.runId)
        assertEquals(
            PINNED_TWO_CYCLE_HOST_PHASES.count { it == CognitivePhase.OBSERVE } + 2,
            phases.count { it == CognitivePhase.OBSERVE },
            "one more OBSERVE per executed cycle, and no second bracket: $phases",
        )
        assertEquals(
            listOf(
                CognitivePhase.PERCEIVE, CognitivePhase.RECALL, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.LEARN,
            ),
            phases,
        )
        assertEquals(
            PINNED_TWO_CYCLE_RUN_EVENTS.toSet(),
            runEvents(run.runId).toSet(),
            "the Field adds no event type; the brackets are the only difference",
        )

        val (perceived, observed) = assertIs<RosterHostedRun>(run).fieldSnapshots
        assertNotNull(perceived, "the PERCEIVE snapshot is kept")
        assertNotNull(observed, "and so is the one from the last executed cycle's OBSERVE")
        assertTrue(observed.asOfSequence >= perceived.asOfSequence, "taken later in the same log")
        assertEquals(Ref<Subject>(HOST_ID), perceived.viewer, "as the host seat sees it")
    }

    @Test
    fun `Goal renders the root goal into PERCEIVE and never a PROTECTED row`() = runBlocking<Unit> {
        storeBelief(holder = HOST_ID, learnings = PUBLIC_LESSON, sensitivity = Sensitivity.PUBLIC)
        storeBelief(holder = HOST_ID, learnings = SECRET, sensitivity = Sensitivity.PROTECTED)
        storeBelief(holder = SCOUT_ID, learnings = OTHERS_LESSON, sensitivity = Sensitivity.PRIVATE)

        val host = hostSeat()
        val run = openRun(host, runHost = runHost(field = fieldService), policy = GOAL_POLICY)
        val result = run.execute()
        run.close()

        val prompt = host.perceivePrompt()
        assertTrue(prompt.contains(WORLD_HEADING), prompt)
        assertTrue(prompt.contains("- [external] goal (open): $GOAL_TEXT"), "the run's own root goal: $prompt")
        assertTrue(prompt.contains("- [external] TASK: $PUBLIC_LESSON"), "a belief the host holds: $prompt")
        assertFalse(prompt.contains(SECRET), "a protected row never reaches a model: $prompt")
        assertTrue(prompt.contains("(1 protected entry not shown)"), "but it is counted: $prompt")
        assertFalse(prompt.contains(OTHERS_LESSON), "another seat's private belief is not in the host's view: $prompt")
        assertTrue(result.readExternalContent, "stored material was shown to a model")

        assertFalse(
            host.transport.prompts.filterNot { it.contains("perception module") }.any { it.contains(WORLD_HEADING) },
            "the section is PERCEIVE's alone: no later prompt of the seat carries it",
        )
        assertEquals(RunStatus.Succeeded, result.status)
    }

    @Test
    fun `a snapshot that fails leaves the run as if Off`() = runBlocking<Unit> {
        val failing = object : FieldService {
            override suspend fun snapshot(viewer: Ref<Subject>?, beliefLimit: Int): Result<Field> =
                Result.failure(IllegalStateException("the store is gone"))
        }
        val host = hostSeat()
        val run = openRun(host, runHost = runHost(field = failing), policy = GOAL_POLICY)
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertFalse(host.perceivePrompt().contains(WORLD_HEADING), host.perceivePrompt())
        assertFalse(result.readExternalContent)
        val (perceived, observed) = assertIs<RosterHostedRun>(run).fieldSnapshots
        assertNull(perceived)
        assertNull(observed)
        // The bracket is still entered: the run looked, and found it could not read.
        assertEquals(2, hostPhasesEntered(run.runId).count { it == CognitivePhase.OBSERVE })
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private class Seat(val descriptor: HostedAgent, val transport: CountingUpstreamLlmClient) {
        fun perceivePrompt(): String = transport.prompts.first { it.contains("perception module") }
    }

    private fun hostSeat(): Seat {
        val transport = CountingUpstreamLlmClient(hostAnswers { planJson(searchStep()) })
        return Seat(
            HostedAgent(
                id = HOST_ID,
                role = HOST_ROLE,
                charter = "You are the planner of this run.",
                aiConfiguration = AI_CONFIGURATION,
                upstreamLlmClient = transport,
            ),
            transport,
        )
    }

    private fun scoutSeat(): HostedAgent = HostedAgent(
        id = SCOUT_ID,
        role = SCOUT_ROLE,
        charter = "You are the scout of this run.",
        aiConfiguration = AI_CONFIGURATION,
        upstreamLlmClient = CountingUpstreamLlmClient(fixedAnswer { "unused" }),
    )

    private fun roster(): Roster = RosterConfig(
        host = HOST_ROLE,
        roles = listOf(
            RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
            RoleConfig(id = SCOUT_ROLE, title = "Scout", instructions = PROMPT, tools = setOf(SEARCH_TOOL)),
        ),
    )

    private fun tools(): Set<Tool<*>> = setOf(recordingTool(SEARCH_TOOL, SEARCH_RESULT, searched))

    /** The production host, with the Goal branch `fromEnvironment` wires and [field] in the new slot. */
    private fun runHost(field: FieldService?): RunHost = rosterRunHost(
        createEventApi = door::doorFor,
        agentScope = scope,
        goalBranch = { seatDoor: AgentEventApi -> GoalBranch.over(door.database, seatDoor) },
        field = field,
    )

    private suspend fun openRun(host: Seat, runHost: RunHost, policy: RunPolicy): HostedRun = runHost.open(
        roster = roster(),
        seats = mapOf(HOST_ROLE to host.descriptor, SCOUT_ROLE to scoutSeat()),
        goal = goal(GOAL_TEXT, id = GOAL_ID),
        tools = tools(),
        policy = policy,
    )

    private fun searchStep(): String =
        planStep("Search the archive", SeatTool(SCOUT_ROLE, SEARCH_TOOL).id, SCOUT_ROLE.value)

    /** One knowledge row straight through the repository, as a Field belief [holder] holds. */
    private suspend fun storeBelief(holder: String, learnings: String, sensitivity: Sensitivity) {
        knowledge.storeKnowledge(
            knowledge = Knowledge.FromTask(
                taskId = "task-${learnings.hashCode()}",
                approach = "",
                learnings = learnings,
                timestamp = Clock.System.now(),
            ),
            holder = Ref(holder),
            sensitivity = sensitivity,
            createdByEvent = "event-${learnings.hashCode()}",
        ).getOrThrow()
    }

    /** The phases the host seat entered, in order. */
    private fun hostPhasesEntered(runId: String): List<CognitivePhase> =
        rows(runId)
            .filter { it.source_id == HOST_ID }
            .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
            .map { it.newPhase }

    /**
     * The run's own events — phases, plan, task, tool and memory — as `<source>:<type>`, in order.
     * Spark and snapshot events are left out: they ride the agent scope, not the run. So is the
     * `MilestoneReached` the task lifecycle derives on completion, which lands either side of
     * LEARN's `PhaseExited` from one run to the next; its presence is still pinned by the set
     * comparison in the Goal test.
     */
    private fun runEvents(runId: String): List<String> =
        rows(runId)
            .mapNotNull { row ->
                val event = decode(row)
                val pinned = event is CognitivePhaseEvent || event is PlanEvent || event is TaskEvent ||
                    event is ToolEvent || event is MemoryEvent
                if (pinned && event.eventType != MILESTONE_REACHED) "${row.source_id}:${event.eventType}" else null
            }

    private fun rows(runId: String): List<EventStore> =
        door.database.eventStoreQueries.getEventsByRunId(runId).executeAsList()

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    private companion object {
        const val SDK_ID = "hosted-run-field-test-sdk"
        const val HOST_ID = "seat-host"
        const val SCOUT_ID = "seat-scout"
        const val SEARCH_TOOL = "search"
        const val SEARCH_RESULT = "found three earlier messages"
        const val GOAL_TEXT = "Answer the thread"
        const val GOAL_ID = "goal-ampr-480"
        const val WORLD_HEADING = "# World"
        const val MILESTONE_REACHED = "MilestoneReached"
        const val PUBLIC_LESSON = "the archive answers in under a second"
        const val SECRET = "the thread's author lives at 12 Elm Street"
        const val OTHERS_LESSON = "the scout keeps this to itself"

        val HOST_ROLE = RoleId("planner")
        val SCOUT_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
        val GOAL_POLICY = RunPolicy(observe = ObservePolicy(field = FieldScope.Goal))

        /**
         * The host seat's brackets for a two-cycle run that observes nothing, pinned from the
         * fixture before the Field reached the run: four phases to charge, EXECUTE, then OBSERVE →
         * PLAN → EXECUTE for the second cycle, and LEARN on close. Nothing after the last EXECUTE:
         * with nothing asked and nothing observed, `assess` returns before the bracket.
         */
        val PINNED_TWO_CYCLE_HOST_PHASES: List<CognitivePhase> = listOf(
            CognitivePhase.PERCEIVE, CognitivePhase.RECALL, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
            CognitivePhase.EXECUTE,
            CognitivePhase.OBSERVE, CognitivePhase.PLAN,
            CognitivePhase.EXECUTE,
            CognitivePhase.LEARN,
        )

        /**
         * The same run's own events, pinned from the same fixture. The scout's EXECUTE bracket and
         * tool pair sit inside the host's EXECUTE; the plan-step pairs leave through the scout's door
         * because the scout ran the step. No seat has a memory store, so LEARN stores no Knowledge.
         * Captured from a host with no Field, which is the path every run took before this change.
         */
        val PINNED_TWO_CYCLE_RUN_EVENTS: List<String> = listOf(
            "$HOST_ID:PhaseEntered",
            "$HOST_ID:PhaseExited",
            "$HOST_ID:PhaseEntered",
            "$HOST_ID:KnowledgeRecalled",
            "$HOST_ID:PhaseExited",
            "$HOST_ID:PhaseEntered",
            "$HOST_ID:PhaseExited",
            "$HOST_ID:PhaseEntered",
            "$HOST_ID:TaskStarted",
            "$HOST_ID:PhaseExited",
            "$HOST_ID:PhaseEntered",
            "$SCOUT_ID:PlanStepStarted",
            "$SCOUT_ID:PhaseEntered",
            "$SCOUT_ID:PlanStepAdmitted",
            "$SCOUT_ID:ToolExecutionStarted",
            "$SCOUT_ID:ToolExecutionCompleted",
            "$SCOUT_ID:PhaseExited",
            "$SCOUT_ID:PlanStepCompleted",
            "$HOST_ID:PhaseExited",
            "$HOST_ID:PhaseEntered",
            "$HOST_ID:PhaseExited",
            "$HOST_ID:PhaseEntered",
            "$HOST_ID:PhaseExited",
            "$HOST_ID:PhaseEntered",
            "$SCOUT_ID:PlanStepStarted",
            "$SCOUT_ID:PhaseEntered",
            "$SCOUT_ID:PlanStepAdmitted",
            "$SCOUT_ID:ToolExecutionStarted",
            "$SCOUT_ID:ToolExecutionCompleted",
            "$SCOUT_ID:PhaseExited",
            "$SCOUT_ID:PlanStepCompleted",
            "$HOST_ID:PhaseExited",
            "$HOST_ID:PhaseEntered",
            "$HOST_ID:TaskCompleted",
            "$HOST_ID:PhaseExited",
        )
    }
}
