package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.events.InMemoryEventDoor
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
import link.socket.ampere.field.Belief
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.Field
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.llm.decide.DecisionRequest
import link.socket.ampere.llm.decide.DecisionResponse
import link.socket.ampere.llm.decide.DeterministicDecisionClient
import link.socket.ampere.llm.decide.Judgment
import link.socket.ampere.llm.decide.ModelSnapshot
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-484 (row N8): LEARN may close a cycle.
 *
 * Driven through the production wiring like [RosterHostedRunCadenceTest] — `rosterRunHost` over
 * real doors onto one in-memory database, seats whose memory is a real `KnowledgeRepositoryImpl`
 * on that database, the phase services behind counting fakes — with a host planner scripted per
 * cycle so a chosen cycle fails. The claims:
 *
 * - under `LearnCadence.EveryCycle` a three-cycle run whose cycle 2 fails leaves one
 *   `KnowledgeStored` for cycle 1 and one for cycle 3, each tagged `cycle:<n>` and the seat's
 *   role, through the seat's own door under the run; the close-time rows are what they were — a
 *   `Partial` run stores no close-time Knowledge (H20) and records one outcome;
 * - a `Succeeded` run under `EveryCycle` holds a cycle row per cycle *and* the close row, and the
 *   close row is the one an `AtClose` run writes;
 * - under `WhenLesson(0.7)` with a deterministic judge saying keep after cycle 1 only, one cycle
 *   row; the lesson question is asked alone when nothing asks about drift, and in the receptor's
 *   request beside the drift question under `WhenDrifted`, where the receptor's answer is read;
 * - a lesson judgment that is missing or self-reported stores nothing;
 * - under `AtClose` with a transport on the seat, the event stream is the pin from
 *   [RosterHostedRunFieldTest];
 * - `open` refuses `WhenLesson` on a host seat with no transport; `EveryCycle` needs none.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual clock
 * walks straight past it.
 */
@OptIn(AmpereExperimentalApi::class)
class RosterHostedRunCycleLearnTest {

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

    // ========================================================================
    // EveryCycle
    // ========================================================================

    @Test
    fun `EveryCycle keeps the lessons of cycles 1 and 3 when cycle 2 fails and the close-time LEARN is unchanged`() =
        runBlocking<Unit> {
            val host = hostSeat(decision = null, plans = listOf(SEARCH, FAIL, SEARCH))
            val run = openRun(host, policy = everyCycle(cycles = 3))
            val result = run.execute()
            run.close()

            assertEquals(3, result.cyclesUsed)
            assertEquals(RunStatus.Partial, result.status, "cycle 2's step failed, cycles 1 and 3 succeeded")

            val stored = knowledgeStored(run.runId)
            assertEquals(
                listOf(listOf(SCOUT_ROLE.value, "cycle:1"), listOf(SCOUT_ROLE.value, "cycle:3")),
                stored.map { it.tags },
                "one row per successful cycle, tagged with the role and the cycle; the failed cycle stores nothing",
            )
            stored.forEach { event ->
                assertEquals(SCOUT_ID, event.eventSource.getIdentifier(), "through the seat that ran the step")
                assertEquals(run.runId, event.runId, "under the run")
                assertEquals(MemoryTaskTypes.GENERIC, event.taskType)
            }
            assertEquals(
                2,
                door.database.knowledgeStoreQueries.findKnowledgeByRunId(run.runId).executeAsList().size,
                "two rows in the store, both under the run",
            )
            assertTrue(stored.none { it.tags == listOf(SCOUT_ROLE.value) }, "a Partial run stores no close-time row")
            assertEquals(1, store.outcomes.getOutcomesByTicket(run.runId).getOrThrow().size, "one outcome row")

            assertEquals(
                listOf(
                    CognitivePhase.PERCEIVE, CognitivePhase.RECALL, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                    CognitivePhase.EXECUTE, CognitivePhase.LEARN,
                    CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                    CognitivePhase.EXECUTE,
                    CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                    CognitivePhase.EXECUTE, CognitivePhase.LEARN,
                    CognitivePhase.LEARN,
                ),
                hostPhasesEntered(run.runId),
                "a LEARN bracket closes cycles 1 and 3; the failed cycle has none; the close-time LEARN is still last",
            )
            assertEquals(4, host.transport.callCount, "one PERCEIVE and three PLANs: a cycle's LEARN bills nothing")
            assertTrue(judgments(run.runId).isEmpty(), "no judge under EveryCycle")
        }

    @Test
    fun `EveryCycle on a Succeeded run holds a cycle row per cycle beside the close row an AtClose run writes`() =
        runBlocking<Unit> {
            val reference = hostSeat(decision = null, plans = listOf(SEARCH, SEARCH))
            val asBefore = openRun(reference, policy = RunPolicy(cycles = 2))
            asBefore.execute()
            asBefore.close()
            val closeRowsBefore = knowledgeStored(asBefore.runId)
            assertEquals(listOf(listOf(SCOUT_ROLE.value)), closeRowsBefore.map { it.tags }, "AtClose: one close row")

            val host = hostSeat(decision = null, plans = listOf(SEARCH, SEARCH))
            val run = openRun(host, policy = everyCycle(cycles = 2))
            val result = run.execute()
            run.close()

            assertEquals(RunStatus.Succeeded, result.status)
            val stored = knowledgeStored(run.runId)
            assertEquals(
                listOf(
                    listOf(SCOUT_ROLE.value, "cycle:1"),
                    listOf(SCOUT_ROLE.value, "cycle:2"),
                    listOf(SCOUT_ROLE.value),
                ),
                stored.map { it.tags },
                "two cycle rows, then the close row",
            )
            assertEquals(
                closeRowsBefore.map { it.shape() },
                stored.filter { it.tags == listOf(SCOUT_ROLE.value) }.map { it.shape() },
                "the close row is what an AtClose run writes",
            )
            assertEquals(
                hostPhasesEntered(asBefore.runId).filter { it != CognitivePhase.LEARN } + CognitivePhase.LEARN,
                hostPhasesEntered(run.runId).filter { it != CognitivePhase.LEARN } + CognitivePhase.LEARN,
                "the brackets differ only by the cycle LEARNs",
            )
            assertEquals(
                3,
                hostPhasesEntered(run.runId).count { it == CognitivePhase.LEARN },
                "one LEARN per cycle and one at close",
            )
        }

    // ========================================================================
    // WhenLesson
    // ========================================================================

    @Test
    fun `WhenLesson stores a cycle's lesson only when the judge says keep`() = runBlocking<Unit> {
        val judge = LessonJudge(true, false, false)
        val host = hostSeat(decision = judge.client, plans = listOf(SEARCH, SEARCH, SEARCH))
        val run = openRun(host, policy = whenLesson(cycles = 3))
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(3, result.cyclesUsed)
        assertEquals(
            listOf(listOf(SCOUT_ROLE.value, "cycle:1"), listOf(SCOUT_ROLE.value)),
            knowledgeStored(run.runId).map { it.tags },
            "cycle 1's lesson was kept, cycles 2 and 3 were judged routine, and the close row is still written",
        )
        assertEquals(
            List(3) { RunQuestions.LESSON_WORTH_KEEPING_ID },
            judgments(run.runId).map { it.questionId },
            "one lesson question per executed cycle, recorded",
        )
        assertTrue(judgments(run.runId).all { it.cognitivePhase == CognitivePhase.OBSERVE })
        assertEquals(3, judge.requests.size)
        judge.requests.forEach { request ->
            assertEquals(setOf(RunQuestions.LESSON_WORTH_KEEPING_ID), request.questions.keys, "asked alone")
        }
        assertEquals(
            listOf(
                CognitivePhase.PERCEIVE, CognitivePhase.RECALL, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE, CognitivePhase.LEARN,
                CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.LEARN,
            ),
            hostPhasesEntered(run.runId),
            "the cycle's LEARN follows the OBSERVE that asked, on cycle 1 only",
        )
        assertEquals(4, host.transport.callCount, "the decision call is not a generative call")
    }

    @Test
    fun `WhenLesson reads the lesson out of the receptor's request under WhenDrifted`() = runBlocking<Unit> {
        val judge = LessonJudge(true, true)
        val host = hostSeat(decision = judge.client, plans = listOf(SEARCH, SEARCH))
        val run = openRun(
            host,
            runHost = runHost(field = MovingField()),
            policy = RunPolicy(
                cycles = 2,
                observe = ObservePolicy(field = FieldScope.Goal),
                cadence = PhaseCadence(
                    perceive = PerceiveCadence.WhenDrifted(0.6),
                    learn = LearnCadence.WhenLesson(0.7),
                ),
            ),
        )
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(
            listOf(
                listOf(SCOUT_ROLE.value, "cycle:1"),
                listOf(SCOUT_ROLE.value, "cycle:2"),
                listOf(SCOUT_ROLE.value),
            ),
            knowledgeStored(run.runId).map { it.tags },
        )
        assertEquals(2, judge.requests.size, "one request per executed cycle: the receptor's")
        judge.requests.forEach { request ->
            assertEquals(
                setOf(RunQuestions.PERCEPTION_DRIFT_ID, RunQuestions.LESSON_WORTH_KEEPING_ID),
                request.questions.keys,
                "the lesson question rides beside the drift question; it adds no call of its own",
            )
        }
        val records = judgments(run.runId)
        assertEquals(4, records.size)
        records.chunked(2).forEach { (drift, lesson) ->
            assertEquals(drift.callId, lesson.callId, "one request")
            assertEquals(drift.stateDigest, lesson.stateDigest, "one state")
        }
        assertEquals(1, host.perceivePrompts().size, "the judge said the world held, so nothing re-perceived")
    }

    @Test
    fun `a missing or self-reported lesson judgment stores nothing`() = runBlocking<Unit> {
        val broken = hostSeat(decision = BrokenDecisionClient, plans = listOf(SEARCH, SEARCH))
        val unanswered = openRun(broken, policy = whenLesson(cycles = 2))
        val unansweredResult = unanswered.execute()
        unanswered.close()

        assertEquals(RunStatus.Succeeded, unansweredResult.status, "a transport that throws costs the run nothing")
        assertTrue(judgments(unanswered.runId).isEmpty(), "a call that threw records no judgment")
        assertEquals(
            listOf(listOf(SCOUT_ROLE.value)),
            knowledgeStored(unanswered.runId).map { it.tags },
            "no cycle row; the close row alone",
        )

        val host = hostSeat(decision = SelfReportedClient, plans = listOf(SEARCH, SEARCH))
        val unmeasured = openRun(host, policy = whenLesson(cycles = 2))
        val unmeasuredResult = unmeasured.execute()
        unmeasured.close()

        assertEquals(RunStatus.Succeeded, unmeasuredResult.status)
        assertEquals(2, judgments(unmeasured.runId).size, "a self-reported answer is still recorded")
        assertTrue(judgments(unmeasured.runId).all { it.answer == Question.Noul.TRUE_KEY })
        assertEquals(
            listOf(listOf(SCOUT_ROLE.value)),
            knowledgeStored(unmeasured.runId).map { it.tags },
            "a 'true' with no measured distribution is not read as a number: nothing stored",
        )
    }

    // ========================================================================
    // AtClose
    // ========================================================================

    @Test
    fun `AtClose with a transport on the seat is the pinned run`() = runBlocking<Unit> {
        val judge = LessonJudge(true, true)
        val host = hostSeat(decision = judge.client, plans = listOf(SEARCH, SEARCH), memory = null)
        val run = openRun(host, scout = scoutSeat(memory = null), policy = RunPolicy(cycles = 2))
        run.execute()
        run.close()

        assertEquals(PINNED_TWO_CYCLE_HOST_PHASES, hostPhasesEntered(run.runId))
        assertEquals(PINNED_TWO_CYCLE_RUN_EVENTS, runEvents(run.runId))
        assertTrue(judgments(run.runId).isEmpty())
        assertTrue(judge.requests.isEmpty(), "AtClose asks nothing about lessons")
    }

    // ========================================================================
    // open refuses
    // ========================================================================

    @Test
    fun `open refuses WhenLesson on a host seat with no transport and EveryCycle needs none`() = runBlocking<Unit> {
        val noTransport = hostSeat(decision = null, plans = listOf(SEARCH))
        val refused = assertFailsWith<IllegalArgumentException> {
            openRun(noTransport, policy = RunPolicy(cadence = PhaseCadence(learn = LearnCadence.WhenLesson(0.7))))
        }
        assertTrue(refused.message!!.startsWith("LearnCadence.WhenLesson asks the host seat"), refused.message)
        assertTrue(refused.message!!.contains("upstreamDecisionClient"), refused.message)
        assertEquals(0, noTransport.transport.callCount, "no seat was built, so nothing perceived")

        val run = openRun(noTransport, policy = RunPolicy(cadence = PhaseCadence(learn = LearnCadence.EveryCycle)))
        val result = run.execute()
        run.close()
        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(
            listOf(listOf(SCOUT_ROLE.value, "cycle:1"), listOf(SCOUT_ROLE.value)),
            knowledgeStored(run.runId).map { it.tags },
        )
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /** A Field that moves on every read: one more lesson held by the host each time. */
    private class MovingField : FieldService {
        private var reads = 0

        override suspend fun snapshot(viewer: Ref<Subject>?, beliefLimit: Int): Result<Field> {
            reads += 1
            return Result.success(
                Field(
                    viewer = viewer,
                    asOfSequence = reads.toLong(),
                    subjects = listOf(
                        Subject.Agent(id = Ref(HOST_ID), name = "Host", provenance = provenance(0)),
                        Subject.Agent(id = Ref(SCOUT_ID), name = "Scout", provenance = provenance(0)),
                    ),
                    relationships = emptyList(),
                    goals = emptyList(),
                    beliefs = (reads downTo 1).map { read ->
                        Belief.Settled(
                            holder = Ref(HOST_ID),
                            subject = Ref<Goal>(GOAL_ID),
                            content = "the archive moved on read $read",
                            kind = BeliefKind.LESSON,
                            confidence = 0.9,
                            sensitivity = Sensitivity.PUBLIC,
                            provenance = provenance(read),
                        )
                    },
                    observations = emptyList(),
                ),
            )
        }

        private fun provenance(read: Int) =
            Provenance(createdBy = "event-$read", createdAt = Instant.fromEpochSeconds(read.toLong()))
    }

    /**
     * A measured one-hot judge answering the lesson question from a script, one answer per
     * request, and every other question `false`; keeps every request it was handed.
     */
    private class LessonJudge(vararg keep: Boolean) {
        val requests: MutableList<DecisionRequest> = CopyOnWriteArrayList()
        private val script = keep.toMutableList()

        private val inner = DeterministicDecisionClient(name = "lesson-judge") { _, question ->
            if (question === RunQuestions.lessonWorthKeeping) {
                val answer = script.removeFirstOrNull() ?: false
                if (answer) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY
            } else {
                Question.Noul.FALSE_KEY
            }
        }

        val client: UpstreamDecisionClient = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse {
                requests += request
                return inner.decide(request, configuration)
            }
        }
    }

    private class Seat(val descriptor: HostedAgent, val transport: CountingUpstreamLlmClient) {
        fun perceivePrompts(): List<String> = transport.prompts.filter { it.contains("perception module") }
    }

    /** The host seat, planning [plans] in order — one per PLAN call, the last repeated. */
    private fun hostSeat(decision: UpstreamDecisionClient?, plans: List<String>, memory: MemoryStore? = store): Seat {
        val planCalls = AtomicInteger()
        val transport = CountingUpstreamLlmClient(
            hostAnswers { planJson(plans[minOf(planCalls.getAndIncrement(), plans.lastIndex)]) },
        )
        return Seat(
            HostedAgent(
                id = HOST_ID,
                role = HOST_ROLE,
                charter = "You are the planner of this run.",
                aiConfiguration = AI_CONFIGURATION,
                upstreamLlmClient = transport,
                upstreamDecisionClient = decision,
                memory = memory,
            ),
            transport,
        )
    }

    private fun scoutSeat(memory: MemoryStore? = store): HostedAgent = HostedAgent(
        id = SCOUT_ID,
        role = SCOUT_ROLE,
        charter = "You are the scout of this run.",
        aiConfiguration = AI_CONFIGURATION,
        upstreamLlmClient = CountingUpstreamLlmClient(fixedAnswer { "unused" }),
        memory = memory,
    )

    private fun roster(): Roster = RosterConfig(
        host = HOST_ROLE,
        roles = listOf(
            RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
            RoleConfig(id = SCOUT_ROLE, title = "Scout", instructions = PROMPT, tools = setOf(SEARCH_TOOL, FAIL_TOOL)),
        ),
    )

    private fun tools(): Set<Tool<*>> = setOf(
        recordingTool(SEARCH_TOOL, "found three earlier messages", dispatched),
        failingTool(FAIL_TOOL, "the index is unreachable", dispatched),
    )

    private fun runHost(field: FieldService? = null): RunHost = rosterRunHost(
        createEventApi = door::doorFor,
        agentScope = scope,
        field = field,
    )

    private suspend fun openRun(
        host: Seat,
        policy: RunPolicy,
        runHost: RunHost = runHost(),
        scout: HostedAgent = scoutSeat(),
    ): HostedRun = runHost.open(
        roster = roster(),
        seats = mapOf(HOST_ROLE to host.descriptor, SCOUT_ROLE to scout),
        goal = goal(GOAL_TEXT, id = GOAL_ID),
        tools = tools(),
        policy = policy,
    )

    private fun knowledgeStored(runId: String): List<MemoryEvent.KnowledgeStored> =
        rows(runId).mapNotNull { decode(it) as? MemoryEvent.KnowledgeStored }

    /** What a close-time row looks like apart from the ids and times that differ per run. */
    private fun MemoryEvent.KnowledgeStored.shape(): List<String?> =
        listOf(eventSource.getIdentifier(), knowledgeType.name, taskType, tags.joinToString())

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
        const val SDK_ID = "hosted-run-cycle-learn-test-sdk"
        const val HOST_ID = "seat-host"
        const val SCOUT_ID = "seat-scout"
        const val SEARCH_TOOL = "search"
        const val FAIL_TOOL = "index"
        const val GOAL_TEXT = "Answer the thread"
        const val GOAL_ID = "goal-ampr-484"
        const val MILESTONE_REACHED = "MilestoneReached"

        val HOST_ROLE = RoleId("planner")
        val SCOUT_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )

        /** A policy that learns after every successful cycle. */
        fun everyCycle(cycles: Int): RunPolicy =
            RunPolicy(cycles = cycles, cadence = PhaseCadence(learn = LearnCadence.EveryCycle))

        /** A policy that learns after a successful cycle whose lesson the judge puts 0.7 or more on. */
        fun whenLesson(cycles: Int): RunPolicy =
            RunPolicy(cycles = cycles, cadence = PhaseCadence(learn = LearnCadence.WhenLesson(0.7)))

        /** A plan with one step on the scout seat that succeeds. */
        val SEARCH: String = planStep("Search the archive", SeatTool(SCOUT_ROLE, SEARCH_TOOL).id, SCOUT_ROLE.value)

        /** A plan with one step on the scout seat that fails. */
        val FAIL: String = planStep("Rebuild the index", SeatTool(SCOUT_ROLE, FAIL_TOOL).id, SCOUT_ROLE.value)

        val BrokenDecisionClient = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse =
                throw IllegalStateException("decision endpoint down")
        }

        /** Answers every question `true` with no measured distribution: a self-reported judgment. */
        val SelfReportedClient = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse =
                DecisionResponse(
                    judgments = request.questions.mapValues {
                        Judgment(
                            answer = Question.Noul.TRUE_KEY,
                            distribution = null,
                            source = ConfidenceSource.SELF_REPORTED,
                            modelSnapshot = ModelSnapshot(providerId = "test", modelId = "self-reported"),
                            locality = InferenceLocality.ON_DEVICE,
                        )
                    },
                )
        }

        /** The host seat's brackets for a two-cycle run that asks nothing: the FieldTest pin. */
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
