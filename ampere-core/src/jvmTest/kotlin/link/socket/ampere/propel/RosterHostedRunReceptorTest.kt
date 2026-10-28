package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.api.AmpereExperimentalApi
import link.socket.ampere.api.service.FieldService
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.field.Field
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject
import link.socket.ampere.goal.GoalBranch
import link.socket.ampere.llm.decide.DecisionRequest
import link.socket.ampere.llm.decide.DecisionResponse
import link.socket.ampere.llm.decide.DeterministicDecisionClient
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-481 (N6, N7): the after-cycle request, record-only.
 *
 * Driven through the production wiring like [RosterHostedRunFieldTest] — `rosterRunHost` over
 * real doors onto one in-memory database, the real `FieldService` over the same database, the
 * real phase services behind counting fakes — with a [DeterministicDecisionClient] on the host
 * seat that remembers every request it was given. Four claims:
 *
 * - Under both `Record` cadences a two-cycle run leaves exactly two `JudgmentRecorded` per cycle
 *   under the receptor ids (and `goal_met`'s beside them under `GoalCheck.Record`), all filed
 *   under OBSERVE; the two receptor records of a cycle share one `stateDigest` and one request,
 *   and differ from `goal_met`'s.
 * - A transport that throws leaves no receptor record and the run's result is unchanged.
 * - The default cadence is the pinned run from [RosterHostedRunFieldTest], event for event.
 * - `open` refuses a cadence that would ask with no transport or no Field, before any seat is
 *   built or any event is published.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual clock
 * walks straight past it.
 */
@OptIn(AmpereExperimentalApi::class)
class RosterHostedRunReceptorTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var fieldService: FieldService

    private val searched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
        val knowledge = KnowledgeRepositoryImpl(door.database, door.driver)
        fieldService = FieldService.over(door.database, knowledge, door.repository)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    // ========================================================================
    // Both cadences on
    // ========================================================================

    @Test
    fun `both Record cadences leave two receptor records per cycle beside the goal's`() = runBlocking<Unit> {
        val judge = Judge()
        val host = hostSeat(decision = judge.client)
        val run = openRun(
            host,
            runHost = runHost(field = fieldService),
            policy = RECEPTOR_POLICY.copy(cycles = 2, goalCheck = GoalCheck.Record),
        )
        val result = run.execute()
        run.close()

        assertEquals(2, result.cyclesUsed)
        assertEquals(RunStatus.Succeeded, result.status)

        val records = judgments(run.runId)
        assertEquals(
            listOf(
                RunQuestions.GOAL_MET_ID,
                RunQuestions.PERCEPTION_DRIFT_ID,
                RunQuestions.LESSON_WORTH_KEEPING_ID,
                RunQuestions.GOAL_MET_ID,
                RunQuestions.PERCEPTION_DRIFT_ID,
                RunQuestions.LESSON_WORTH_KEEPING_ID,
            ),
            records.map { it.questionId },
            "per executed cycle: the goal's record, then the receptor's two",
        )
        assertTrue(records.all { it.cognitivePhase == CognitivePhase.OBSERVE }, "all filed under OBSERVE")
        assertTrue(records.all { it.agentId == HOST_ID }, "all on the host seat")

        records.chunked(3).forEachIndexed { index, cycle ->
            val (goal, drift, lesson) = cycle
            assertEquals(drift.stateDigest, lesson.stateDigest, "cycle ${index + 1}: one state for the receptor")
            assertEquals(drift.callId, lesson.callId, "cycle ${index + 1}: one request for the receptor")
            assertNotEquals(goal.stateDigest, drift.stateDigest, "cycle ${index + 1}: the goal's state is its own")
            assertNotEquals(goal.callId, drift.callId, "cycle ${index + 1}: the goal's request is its own")
        }
        assertEquals(3, records.map { it.questionVersion }.distinct().size, "three questions, three versions")

        // The requests the judge saw: per cycle one carrying `goal_met` alone over the
        // observation, then one carrying both receptor questions over the drift.
        assertEquals(4, judge.requests.size)
        judge.requests.chunked(2).forEachIndexed { index, (goalRequest, receptorRequest) ->
            assertEquals(setOf(RunQuestions.GOAL_MET_ID), goalRequest.questions.keys)
            assertEquals(
                setOf(RunQuestions.PERCEPTION_DRIFT_ID, RunQuestions.LESSON_WORTH_KEEPING_ID),
                receptorRequest.questions.keys,
            )
            assertFalse(
                goalRequest.state.contains("\"changes\""),
                "the goal's state is AMPR-421's: ${goalRequest.state}",
            )
            val drift = Json.parseToJsonElement(receptorRequest.state).jsonObject
            assertEquals(
                listOf("goal", "cycle", "perceived", "changes", "cycle_results"),
                drift.keys.toList(),
                "cycle ${index + 1}: the receptor's state in its fixed key order",
            )
            assertEquals("${index + 1}", drift.getValue("cycle").toString())
            assertEquals("\"$GOAL_TEXT\"", drift.getValue("goal").toString())
            assertTrue(
                receptorRequest.state.contains(SEARCH_RESULT),
                "this cycle's results, as the tool reported them: ${receptorRequest.state}",
            )
            assertEquals(
                1,
                Regex("\"step\":").findAll(receptorRequest.state).count(),
                "cycle ${index + 1}: this cycle's one step, not every cycle's: ${receptorRequest.state}",
            )
        }
        assertTrue(result.readExternalContent, "the search tool's External result was shown to a model")
    }

    @Test
    fun `the lesson cadence alone asks one question over empty changes without a Field`() = runBlocking<Unit> {
        val judge = Judge()
        val host = hostSeat(decision = judge.client)
        val run = openRun(
            host,
            runHost = runHost(field = null),
            policy = RunPolicy(cadence = PhaseCadence(learn = LearnCadence.Record)),
        )
        run.execute()
        run.close()

        assertEquals(listOf(RunQuestions.LESSON_WORTH_KEEPING_ID), judgments(run.runId).map { it.questionId })
        val request = judge.requests.single()
        assertTrue(
            request.state.contains("\"changes\":{\"goals\":[],\"commitments\":{\"opened\":[],\"resolved\":[]}"),
            "nothing to fold renders as nothing moved: ${request.state}",
        )
        assertEquals(
            listOf(CognitivePhase.OBSERVE),
            hostPhasesEntered(run.runId).drop(PINNED_ONE_CYCLE_HOST_PHASES.size - 1).dropLast(1),
            "one OBSERVE bracket after the executed cycle, in which the question was asked",
        )
    }

    @Test
    fun `a snapshot that fails leaves drift unasked and the lesson asked`() = runBlocking<Unit> {
        val failing = object : FieldService {
            override suspend fun snapshot(viewer: Ref<Subject>?, beliefLimit: Int): Result<Field> =
                Result.failure(IllegalStateException("the store is gone"))
        }
        val judge = Judge()
        val host = hostSeat(decision = judge.client)
        val run = openRun(host, runHost = runHost(field = failing), policy = RECEPTOR_POLICY.copy(cycles = 2))
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(
            listOf(RunQuestions.LESSON_WORTH_KEEPING_ID, RunQuestions.LESSON_WORTH_KEEPING_ID),
            judgments(run.runId).map { it.questionId },
            "no snapshot, no drift to ask about; the lesson question still goes",
        )
    }

    // ========================================================================
    // Record-only
    // ========================================================================

    @Test
    fun `a transport that throws leaves no receptor record and the result unchanged`() = runBlocking<Unit> {
        val working = hostSeat(decision = Judge().client)
        val withRecords = openRun(
            working,
            runHost = runHost(field = fieldService),
            policy = RECEPTOR_POLICY.copy(cycles = 2),
        )
        val expected = withRecords.execute()
        withRecords.close()
        assertEquals(4, judgments(withRecords.runId).size, "the fixture does record when the transport answers")

        val broken = hostSeat(decision = BrokenDecisionClient)
        val run = openRun(broken, runHost = runHost(field = fieldService), policy = RECEPTOR_POLICY.copy(cycles = 2))
        val result = run.execute()
        run.close()

        assertTrue(judgments(run.runId).isEmpty(), "a call that threw records nothing")
        assertEquals(expected.status, result.status)
        assertEquals(expected.cyclesUsed, result.cyclesUsed)
        assertEquals(expected.stepOutcomes.size, result.stepOutcomes.size)
        assertEquals(expected.readExternalContent, result.readExternalContent)
        assertEquals(hostPhasesEntered(withRecords.runId), hostPhasesEntered(run.runId), "the brackets are the same")
        assertEquals(
            runEvents(withRecords.runId),
            runEvents(run.runId),
            "and so is every event of the run that is not a judgment",
        )
    }

    @Test
    fun `the default cadence is the pinned run and asks nothing`() = runBlocking<Unit> {
        val judge = Judge()
        val host = hostSeat(decision = judge.client)
        val run = openRun(host, runHost = runHost(field = fieldService), policy = RunPolicy(cycles = 2))
        run.execute()
        run.close()

        assertEquals(PINNED_TWO_CYCLE_HOST_PHASES, hostPhasesEntered(run.runId))
        assertEquals(PINNED_TWO_CYCLE_RUN_EVENTS, runEvents(run.runId))
        assertTrue(judgments(run.runId).isEmpty(), "a transport on the seat is inert until a policy asks")
        assertTrue(judge.requests.isEmpty())
    }

    @Test
    fun `a Goal policy with the default cadence observes and asks nothing`() = runBlocking<Unit> {
        val judge = Judge()
        val host = hostSeat(decision = judge.client)
        val run = openRun(host, runHost = runHost(field = fieldService), policy = GOAL_POLICY.copy(cycles = 2))
        run.execute()
        run.close()

        assertEquals(
            PINNED_TWO_CYCLE_RUN_EVENTS.toSet(),
            runEvents(run.runId).toSet(),
            "the Field adds no event type and the cadence adds no record",
        )
        assertTrue(judgments(run.runId).isEmpty())
    }

    // ========================================================================
    // open refuses
    // ========================================================================

    @Test
    fun `open refuses a Record cadence on a host seat with no decision transport`() = runBlocking<Unit> {
        val host = hostSeat(decision = null)

        val drift = assertFailsWith<IllegalArgumentException> {
            openRun(host, runHost = runHost(field = fieldService), policy = RECEPTOR_POLICY)
        }
        assertTrue(drift.message!!.contains("PerceiveCadence.Record and LearnCadence.Record"), drift.message)
        assertTrue(drift.message!!.contains("upstreamDecisionClient"), drift.message)

        val lesson = assertFailsWith<IllegalArgumentException> {
            openRun(
                host,
                runHost = runHost(field = fieldService),
                policy = RunPolicy(cadence = PhaseCadence(learn = LearnCadence.Record)),
            )
        }
        assertTrue(lesson.message!!.startsWith("LearnCadence.Record asks the host seat"), lesson.message)

        assertNothingBuilt(host)
    }

    @Test
    fun `open refuses a drift cadence when the policy does not observe the Field`() = runBlocking<Unit> {
        val host = hostSeat(decision = Judge().client)

        val refused = assertFailsWith<IllegalArgumentException> {
            openRun(
                host,
                runHost = runHost(field = fieldService),
                policy = RunPolicy(cadence = PhaseCadence(perceive = PerceiveCadence.Record)),
            )
        }
        assertTrue(refused.message!!.contains("FieldScope.Off"), refused.message)
        assertTrue(refused.message!!.contains("a drift question needs a world to have drifted"), refused.message)
        assertNothingBuilt(host)
    }

    @Test
    fun `open refuses a drift cadence on a host with no Field`() = runBlocking<Unit> {
        val host = hostSeat(decision = Judge().client)

        val refused = assertFailsWith<IllegalArgumentException> {
            openRun(host, runHost = runHost(field = null), policy = RECEPTOR_POLICY)
        }
        assertTrue(refused.message!!.contains("no FieldService"), refused.message)
        assertTrue(refused.message!!.contains("a drift question needs a world to have drifted"), refused.message)
        assertNothingBuilt(host)
    }

    private fun assertNothingBuilt(host: Seat) {
        assertEquals(0, host.transport.callCount, "no seat was built, so nothing perceived")
        assertTrue(
            door.database.eventStoreQueries.getAllEvents().executeAsList().none { it.source_id == HOST_ID },
            "and nothing was published under the host seat",
        )
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /** A [DeterministicDecisionClient] answering `true` that keeps every request it was handed. */
    private class Judge {
        val requests: MutableList<DecisionRequest> = CopyOnWriteArrayList()

        private val inner = DeterministicDecisionClient(name = "test-judge") { _, _ -> Question.Noul.TRUE_KEY }

        val client: UpstreamDecisionClient = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse {
                requests += request
                return inner.decide(request, configuration)
            }
        }
    }

    private class Seat(val descriptor: HostedAgent, val transport: CountingUpstreamLlmClient)

    private fun hostSeat(decision: UpstreamDecisionClient?): Seat {
        val transport = CountingUpstreamLlmClient(hostAnswers { planJson(searchStep()) })
        return Seat(
            HostedAgent(
                id = HOST_ID,
                role = HOST_ROLE,
                charter = "You are the planner of this run.",
                aiConfiguration = AI_CONFIGURATION,
                upstreamLlmClient = transport,
                upstreamDecisionClient = decision,
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

    /** The production host, with the Goal branch `fromEnvironment` wires and [field] in its slot. */
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

    private fun judgments(runId: String): List<CognitiveEvent.JudgmentRecorded> =
        rows(runId).mapNotNull { decode(it) as? CognitiveEvent.JudgmentRecorded }

    /** The phases the host seat entered, in order. */
    private fun hostPhasesEntered(runId: String): List<CognitivePhase> =
        rows(runId)
            .filter { it.source_id == HOST_ID }
            .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
            .map { it.newPhase }

    /** The run's own events as `<source>:<type>`, in order, as [RosterHostedRunFieldTest] pins them. */
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
        const val SDK_ID = "hosted-run-receptor-test-sdk"
        const val HOST_ID = "seat-host"
        const val SCOUT_ID = "seat-scout"
        const val SEARCH_TOOL = "search"
        const val SEARCH_RESULT = "found three earlier messages"
        const val GOAL_TEXT = "Answer the thread"
        const val GOAL_ID = "goal-ampr-481"
        const val MILESTONE_REACHED = "MilestoneReached"

        val HOST_ROLE = RoleId("planner")
        val SCOUT_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
        val GOAL_POLICY = RunPolicy(observe = ObservePolicy(field = FieldScope.Goal))

        /** Both receptor questions on, over a run that observes the Field. */
        val RECEPTOR_POLICY = GOAL_POLICY.copy(
            cadence = PhaseCadence(perceive = PerceiveCadence.Record, learn = LearnCadence.Record),
        )

        val BrokenDecisionClient = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse =
                throw IllegalStateException("decision endpoint down")
        }

        /** The host seat's brackets for a one-cycle run that asks and observes nothing. */
        val PINNED_ONE_CYCLE_HOST_PHASES: List<CognitivePhase> = listOf(
            CognitivePhase.PERCEIVE,
            CognitivePhase.RECALL,
            CognitivePhase.OBSERVE,
            CognitivePhase.PLAN,
            CognitivePhase.EXECUTE,
            CognitivePhase.LEARN,
        )

        /**
         * The host seat's brackets for a two-cycle run that observes and asks nothing: the pin
         * [RosterHostedRunFieldTest] holds, repeated here because the pin is that test's own.
         */
        val PINNED_TWO_CYCLE_HOST_PHASES: List<CognitivePhase> = listOf(
            CognitivePhase.PERCEIVE, CognitivePhase.RECALL, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
            CognitivePhase.EXECUTE,
            CognitivePhase.OBSERVE, CognitivePhase.PLAN,
            CognitivePhase.EXECUTE,
            CognitivePhase.LEARN,
        )

        /** The same run's own events, as [RosterHostedRunFieldTest] pins them. */
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
            "$SCOUT_ID:ToolExecutionProgress",
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
            "$SCOUT_ID:ToolExecutionProgress",
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
