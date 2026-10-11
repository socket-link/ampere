package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.CognitiveEvent
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.llm.decide.DecisionRequest
import link.socket.ampere.llm.decide.DecisionResponse
import link.socket.ampere.llm.decide.DeterministicDecisionClient
import link.socket.ampere.llm.decide.MissingUpstreamDecisionClientException
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.llm.decide.stateDigest
import link.socket.ampere.llm.decide.version
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-421: the first callers of `decide`, record-only.
 *
 * A hosted run can be handed a decision transport per seat and asked to put two kinds
 * of question after each executed cycle — its own "is the goal met?" on the host seat,
 * and the verifier seat's declared questions. Every answer leaves a `JudgmentRecorded`
 * and nothing reads it, which is the claim the last case here exists to pin: the same
 * fixture run with the check off, with a judge answering `true` and with one answering
 * `false` settles identically.
 *
 * Driven through the production wiring like [HostedRunTest] — real doors onto one
 * store, a real `FunctionExecutor`, real phase services behind counting fakes — because
 * the claims are about rows a store actually holds and calls a transport actually
 * received.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a
 * virtual clock walks straight past it.
 */
class HostedRunJudgmentTest {

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
    // Task 1: the seat carries a transport (H21)
    // ========================================================================

    /**
     * Both halves of what [HostedAgent.upstreamDecisionClient] being nullable means.
     *
     * The second half is asserted on a seat's agent rather than through [RunHost.open],
     * because `open` refuses a run that would reach it (see the two cases under task 4)
     * — which is the point: the exception is what the property's KDoc promises and what
     * the refusal exists to keep unreachable, so it is worth pinning that the promise
     * is true rather than only that the refusal fires.
     */
    @Test
    fun `a seat given a decision client can decide, and one without throws`() = runBlocking<Unit> {
        val judge = Judge(answering = Question.Noul.TRUE_KEY)
        val host = seat(HOST_ID, HOST_ROLE, decision = judge.client) { toolPlan() }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer, policy = RunPolicy(goalCheck = GoalCheck.Record))
        run.execute()
        run.close()

        assertEquals(
            1,
            judgments(run.runId).size,
            "the seat's transport was reached, which only the wiring in buildSeat does",
        )

        val undecided = SparkBasedAgent(
            agentId = "seat-with-no-decision-transport",
            cognitiveAffinity = CognitiveAffinity.INTEGRATIVE,
            initialState = AgentState(),
            _aiConfiguration = AI_CONFIGURATION,
            _upstreamLlmClient = CountingUpstreamLlmClient { "" },
        )
        assertFailsWith<MissingUpstreamDecisionClientException> {
            undecided.reasoningUnit.decide(
                state = "{}",
                questions = mapOf(RunQuestions.GOAL_MET_ID to RunQuestions.goalMet),
            )
        }
    }

    // ========================================================================
    // Task 2: the question and the state
    // ========================================================================

    @Test
    fun `the state of the same observation is the same string twice`() {
        val observation = twoStepObservation()

        assertEquals(
            observation.asDecisionState(CLIP),
            observation.asDecisionState(CLIP),
            "the record's stateDigest is the only join key back to a state, so the same " +
                "observation has to render the same string — no ids, no timestamps",
        )
        assertEquals(
            observation.asDecisionState(CLIP),
            twoStepObservation().asDecisionState(CLIP),
            "and so does an equal observation built again",
        )
    }

    @Test
    fun `a long result is clipped`() {
        val long = "x".repeat(50)
        val state = RunObservation(
            runId = "run-clipped",
            goal = goal(GOAL_TEXT),
            cycle = 1,
            priorResults = listOf(succeeded("Search", long)),
        ).asDecisionState(clip = 10)

        assertTrue(state.contains("\"result\":\"xxxxxxxxxx…\""), "cut at the clip, with an ellipsis: $state")
        assertTrue(!state.contains("x".repeat(11)), "and nothing past it reaches the state: $state")
        assertFailsWith<IllegalArgumentException> { twoStepObservation().asDecisionState(clip = 0) }
    }

    @Test
    fun `a two-step observation renders one fixed string`() {
        assertEquals(GOLDEN_STATE, twoStepObservation().asDecisionState(CLIP))
    }

    // ========================================================================
    // Task 3: the policy
    // ========================================================================

    @Test
    fun `the default policy asks nothing`() = runBlocking<Unit> {
        assertEquals(GoalCheck.Off, RunPolicy().goalCheck, "the default run asks itself nothing")
        assertEquals(400, RunPolicy().judgmentResultClip)
        assertFailsWith<IllegalArgumentException> { RunPolicy(judgmentResultClip = 0) }

        // Both seats carry a transport, and neither is asked: a wired transport is not
        // an opted-into question.
        val hostJudge = Judge(answering = Question.Noul.TRUE_KEY)
        val doerJudge = Judge(answering = Question.Noul.TRUE_KEY)
        val host = seat(HOST_ID, HOST_ROLE, decision = hostJudge.client) { toolPlan() }
        val doer = seat(DOER_ID, DOER_ROLE, decision = doerJudge.client) { TOOL_RESULT }

        val run = openRun(host, doer, verifier = DOER_ROLE)
        run.execute()
        run.close()

        assertTrue(hostJudge.states.isEmpty(), "the host seat's transport was never called")
        assertTrue(doerJudge.states.isEmpty(), "nor the verifier's")
        assertTrue(judgments(run.runId).isEmpty(), "and nothing was recorded")
    }

    // ========================================================================
    // Task 4: a half-configured run is refused at open
    // ========================================================================

    @Test
    fun `open refuses a goal check whose host seat has no decision transport`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) { toolPlan() }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val failure = runCatching {
            openRun(host, doer, policy = RunPolicy(goalCheck = GoalCheck.Record))
        }.exceptionOrNull()

        assertTrue(
            failure is IllegalArgumentException &&
                failure.message.orEmpty().contains("upstreamDecisionClient"),
            "a run that asks for records and can leave none is refused; got $failure",
        )
    }

    @Test
    fun `open refuses a verifier seat whose questions have no decision transport`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE) { toolPlan() }
        val doer = seat(DOER_ID, DOER_ROLE, questions = VERIFIER_QUESTIONS) { TOOL_RESULT }

        val failure = runCatching { openRun(host, doer, verifier = DOER_ROLE) }.exceptionOrNull()

        assertTrue(
            failure is IllegalArgumentException &&
                failure.message.orEmpty().contains(DOER_ROLE.value),
            "the seat that declared the questions is the one named; got $failure",
        )
    }

    /** A seat's questions are the verifier's or nobody's, exactly as its probes are. */
    @Test
    fun `a non-verifier seat's questions are never asked`() = runBlocking<Unit> {
        val judge = Judge(answering = Question.Noul.TRUE_KEY)
        val host = seat(HOST_ID, HOST_ROLE) { toolPlan() }
        val doer = seat(
            DOER_ID,
            DOER_ROLE,
            decision = judge.client,
            questions = VERIFIER_QUESTIONS,
        ) { TOOL_RESULT }

        // The roster names no verifier at all, so the doer's questions belong to nobody.
        val run = openRun(host, doer, verifier = null)
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status, "the run was not refused, and ran")
        assertTrue(judge.states.isEmpty(), "the seat's transport was never called")
        assertTrue(judgments(run.runId).isEmpty(), "and nothing was recorded")
    }

    // ========================================================================
    // Task 5: what a cycle that executed is asked
    // ========================================================================

    @Test
    fun `one executed cycle leaves one goal record on the host seat, under the run`() = runBlocking<Unit> {
        val judge = Judge(answering = Question.Noul.TRUE_KEY)
        val host = seat(HOST_ID, HOST_ROLE, decision = judge.client) { toolPlan() }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer, policy = RunPolicy(goalCheck = GoalCheck.Record))
        run.execute()
        run.close()

        val rows = rows(run.runId).filter { it.event_type == CognitiveEvent.JudgmentRecorded.EVENT_TYPE }
        val row = assertNotNull(rows.singleOrNull(), "one record per executed cycle; got ${rows.size}")
        assertEquals(run.runId, row.run_id, "the run rides on the envelope (J19)")
        assertEquals(HOST_ID, row.source_id, "and the asking seat is the record's provenance")

        val record = assertNotNull(decode(row) as? CognitiveEvent.JudgmentRecorded)
        assertEquals(RunQuestions.GOAL_MET_ID, record.questionId)
        assertEquals("noul", record.questionType)
        assertEquals(RunQuestions.goalMet.version, record.questionVersion)
        assertEquals(CognitivePhase.OBSERVE, record.cognitivePhase, "filed under the phase that asked")
        assertEquals(HOST_ID, record.agentId, "the asking seat's agent id is on the record (J19)")
        assertEquals(Question.Noul.TRUE_KEY, record.answer)
        assertEquals(ConfidenceSource.MEASURED, record.source)

        // --- and the state is the one thing that stayed in the process ---------
        val state = assertNotNull(judge.states.singleOrNull(), "one decision call, one state")
        assertEquals(stateDigest(state), record.stateDigest, "the digest is the join key")
        assertTrue(
            state.contains(TOOL_RESULT),
            "the state is about what the cycle's steps reported: $state",
        )
        val stored = rows(run.runId).map { it.payload }
        val escaped = Json.encodeToString(String.serializer(), state).trim('"')
        assertTrue(
            stored.none { it.contains(state) || it.contains(escaped) },
            "the state text appears in no stored event, raw or escaped",
        )
    }

    @Test
    fun `a two-cycle run leaves two records, and the second state carries both cycles`() = runBlocking<Unit> {
        val judge = Judge(answering = Question.Noul.FALSE_KEY)
        val host = seat(HOST_ID, HOST_ROLE, decision = judge.client) { toolPlan() }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(
            host,
            doer,
            policy = RunPolicy(cycles = 2, goalCheck = GoalCheck.Record),
        )
        val result = run.execute()
        run.close()

        assertEquals(2, result.cyclesUsed)
        assertEquals(2, judgments(run.runId).size, "one record per executed cycle, not per run")
        assertEquals(2, judge.states.size)
        assertTrue(
            judge.states.first().contains("\"cycle\":1"),
            "the first call is about the first cycle: ${judge.states.first()}",
        )
        val second = judge.states.last()
        assertTrue(second.contains("\"cycle\":2"), "and the second about the second: $second")
        assertEquals(
            2,
            Regex(Regex.escape(TOOL_RESULT)).findAll(second).count(),
            "the second state carries both cycles' results: $second",
        )
        assertEquals(
            listOf(Question.Noul.FALSE_KEY, Question.Noul.FALSE_KEY),
            judgments(run.runId).map { it.answer },
            "and a `false` answer twice changed nothing about the run",
        )
    }

    @Test
    fun `a verifier seat's questions are recorded under the verifier's agent id`() = runBlocking<Unit> {
        val hostJudge = Judge(answering = Question.Noul.TRUE_KEY)
        val verifierJudge = Judge(answering = { question -> question.answerKeys.first() })
        val host = seat(HOST_ID, HOST_ROLE, decision = hostJudge.client) { toolPlan() }
        val doer = seat(
            DOER_ID,
            DOER_ROLE,
            decision = verifierJudge.client,
            questions = VERIFIER_QUESTIONS,
        ) { TOOL_RESULT }

        val run = openRun(
            host,
            doer,
            verifier = DOER_ROLE,
            policy = RunPolicy(goalCheck = GoalCheck.Record),
        )
        run.execute()
        run.close()

        val byAgent = judgments(run.runId).groupBy({ it.agentId }) { it.questionId }
        assertEquals(
            mapOf(
                HOST_ID to listOf(RunQuestions.GOAL_MET_ID),
                DOER_ID to VERIFIER_QUESTIONS.keys.toList(),
            ),
            byAgent,
            "the run's question is the host's, the declared ones are the verifier's, and " +
                "all of the verifier's rode in one request",
        )
        assertEquals(
            1,
            judgments(run.runId).filter { it.agentId == DOER_ID }.map { it.callId }.distinct().size,
            "one decision call for the seat, whatever the question count (the cost claim)",
        )
        assertEquals(
            hostJudge.states.distinct(),
            verifierJudge.states.distinct(),
            "both seats are asked about one moment, so the state is rendered once",
        )
        assertTrue(
            judgments(run.runId).map { it.stateDigest }.distinct().size == 1,
            "which a reader sees as one stateDigest across the cycle's records",
        )
    }

    @Test
    fun `a decision transport that throws leaves no record and the run still closes`() = runBlocking<Unit> {
        val host = seat(HOST_ID, HOST_ROLE, decision = BrokenDecisionClient) { toolPlan() }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(host, doer, policy = RunPolicy(goalCheck = GoalCheck.Record))
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status, "the work is done; the question is not the work")
        assertEquals(1, result.stepOutcomes.size)
        assertTrue(judgments(run.runId).isEmpty(), "a call that threw records nothing")
        assertTrue(
            rows(run.runId).any { decode(it) is Event.TaskCreated },
            "and the run closed normally",
        )
        assertEquals(
            1,
            store.outcomes.getOutcomesByTicket(run.runId).getOrThrow().size,
            "LEARN still recorded the run",
        )
    }

    @Test
    fun `a stopped plan asks nothing`() = runBlocking<Unit> {
        val judge = Judge(answering = Question.Noul.TRUE_KEY)
        val host = seat(HOST_ID, HOST_ROLE, decision = judge.client) { toolPlan() }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(
            host,
            doer,
            policy = RunPolicy(planGate = StopAlways, goalCheck = GoalCheck.Record),
        )
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Stopped, result.status)
        assertEquals(Plan.Blank, run.plan)
        assertTrue(judge.states.isEmpty(), "no cycle executed, so there is nothing to judge")
        assertTrue(judgments(run.runId).isEmpty())
    }

    /**
     * Cancelled the way a run actually is, as [HostedRunTest] cancels one: the scope
     * driving it dies while a step is in flight.
     *
     * The judge is a [DeterministicDecisionClient], which has no suspension point to be
     * cancelled at, so it would happily answer on a dead scope and the record would go
     * out under `NonCancellable`. The live check in `assess` is what stops it.
     */
    @Test
    fun `a cancelled run asks nothing`() = runBlocking<Unit> {
        val judge = Judge(answering = Question.Noul.TRUE_KEY)
        val host = seat(HOST_ID, HOST_ROLE, decision = judge.client) {
            planJson(planStep("Search once", BLOCKING_TOOL.seatId(), DOER_ROLE.value))
        }
        val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

        val run = openRun(
            host,
            doer,
            policy = RunPolicy(goalCheck = GoalCheck.Record),
            tools = setOf(searchTool, blockingTool),
        )
        val job = scope.launch { runCatching { run.execute() } }
        stepReached.await()
        job.cancelAndJoin()
        run.close()

        assertTrue(judge.states.isEmpty(), "a cancelled cycle is not a cycle to judge")
        assertTrue(judgments(run.runId).isEmpty())
    }

    // ========================================================================
    // Task 6: nothing reads the answer
    // ========================================================================

    /**
     * The same fixture, three ways: the check off, a judge answering `true`, a judge
     * answering `false`. All three settle identically.
     *
     * The step outcomes are compared as (kind, description) pairs rather than as whole
     * [StepOutcome]s because a step's id is minted and its timestamps are read off the
     * clock, so three runs can never hold equal objects — which is also why those two
     * fields are left out of the decision state.
     *
     * The event stream is compared as the host seat's ordered phase brackets, which are
     * published inline from the run's own coroutine and are therefore the part of the
     * stream that is deterministic; a seat's `SparkAppliedEvent`s go out on the agents'
     * observability scope and have no guaranteed position. `Off` is the six-phase
     * sequence a run had before this ticket; `Record` is that sequence with the one
     * OBSERVE this ticket adds, and nothing else.
     */
    @Test
    fun `an answer changes nothing about the run`() = runBlocking<Unit> {
        val settled = SETTLEMENTS.map { settlement ->
            val judge = Judge(answering = settlement.answer)
            val host = seat(HOST_ID, HOST_ROLE, decision = judge.client) { toolPlan() }
            val doer = seat(DOER_ID, DOER_ROLE) { TOOL_RESULT }

            val run = openRun(host, doer, policy = settlement.policy)
            val result = run.execute()
            run.close()
            Settled(
                name = settlement.label,
                result = result,
                brackets = hostBrackets(run.runId),
                judgments = judgments(run.runId).size,
                eventTypes = rows(run.runId).mapTo(mutableSetOf()) { it.event_type },
            )
        }

        val reference = settled.first()
        settled.drop(1).forEach { other ->
            assertEquals(reference.result.status, other.result.status, "status differs for ${other.name}")
            assertEquals(
                reference.result.cyclesUsed,
                other.result.cyclesUsed,
                "cyclesUsed differs for ${other.name}",
            )
            assertEquals(
                reference.result.stepOutcomes.shape(),
                other.result.stepOutcomes.shape(),
                "stepOutcomes differ for ${other.name}",
            )
        }

        val off = settled.single { it.name == OFF }
        assertEquals(SIX_PHASES, off.brackets, "the default run's phases, in order")
        assertEquals(0, off.judgments, "and it recorded no judgment")
        assertTrue(
            CognitiveEvent.JudgmentRecorded.EVENT_TYPE !in off.eventTypes,
            "which is also to say the type is absent from its stream",
        )

        settled.filter { it.name != OFF }.forEach { recorded ->
            assertEquals(
                RECORDING_PHASES,
                recorded.brackets,
                "a recording run adds exactly one OBSERVE, after EXECUTE (${recorded.name})",
            )
            assertEquals(1, recorded.judgments, "one record per executed cycle (${recorded.name})")
            assertEquals(
                setOf(CognitiveEvent.JudgmentRecorded.EVENT_TYPE),
                recorded.eventTypes - off.eventTypes,
                "and introduces no other kind of event (${recorded.name})",
            )
        }
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /** One run of the three-way comparison: how it was configured. */
    private class Settlement(
        val label: String,
        val answer: String,
        val policy: RunPolicy,
    )

    /** What one run of the three-way comparison settled as. */
    private class Settled(
        val name: String,
        val result: RunResult,
        val brackets: List<String>,
        val judgments: Int,
        val eventTypes: Set<String>,
    )

    /**
     * A [DeterministicDecisionClient] that remembers the state it was handed.
     *
     * Deterministic rather than a hand-written transport so the answer is a
     * `MEASURED`, one-hot judgment — the honest shape for a function — and so the three
     * runs of the last case differ only in the key the judge returns.
     */
    private class Judge(private val answering: (Question) -> String) {

        constructor(answering: String) : this({ answering })

        /** Every state this judge was given, once per question asked of it. */
        val states: MutableList<String> = CopyOnWriteArrayList()

        val client: UpstreamDecisionClient =
            DeterministicDecisionClient(name = "test-judge") { state, question ->
                states += state
                answering(question)
            }
    }

    private class Seat(
        val descriptor: HostedAgent,
        val transport: CountingUpstreamLlmClient,
    )

    private fun seat(
        id: String,
        role: RoleId,
        decision: UpstreamDecisionClient? = null,
        questions: Map<String, Question> = emptyMap(),
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
                questions = questions,
                upstreamDecisionClient = decision,
            ),
            transport = transport,
        )
    }

    private suspend fun openRun(
        host: Seat,
        doer: Seat,
        verifier: RoleId? = null,
        policy: RunPolicy = RunPolicy(),
        tools: Set<Tool<*>> = setOf(searchTool),
    ): HostedRun = rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
        roster = RosterConfig(
            host = HOST_ROLE,
            verifier = verifier,
            roles = listOf(
                RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
                RoleConfig(
                    id = DOER_ROLE,
                    title = "Doer",
                    instructions = PROMPT,
                    tools = setOf(DOER_TOOL, BLOCKING_TOOL),
                ),
            ),
        ),
        seats = mapOf(HOST_ROLE to host.descriptor, DOER_ROLE to doer.descriptor),
        goal = goal(GOAL_TEXT),
        tools = tools,
        policy = policy,
    )

    /** One tool step for the doer seat, so the host's own brackets stay unnested. */
    private fun toolPlan(): String =
        planJson(planStep("Search for the retry policy", DOER_TOOL.seatId(), DOER_ROLE.value))

    private val searchTool = recordingTool(DOER_TOOL, TOOL_RESULT, dispatched)

    /** Completed once [blockingTool] has been dispatched, so the test knows when to cancel. */
    private val stepReached = CompletableDeferred<Unit>()

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

    private fun rows(runId: String): List<EventStore> =
        door.database.eventStoreQueries.getEventsByRunId(runId).executeAsList()

    private fun judgments(runId: String): List<CognitiveEvent.JudgmentRecorded> =
        rows(runId).mapNotNull { decode(it) as? CognitiveEvent.JudgmentRecorded }

    /** The phases the host seat entered, in order. */
    private fun hostBrackets(runId: String): List<String> = rows(runId)
        .filter { it.source_id == HOST_ID }
        .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
        .map { it.newPhase.name }

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    /** The id this run offers the planner for a tool of the doer seat: `<seat>/<tool>`. */
    private fun String.seatId(): String = SeatTool(DOER_ROLE, this).id

    private companion object {
        const val SDK_ID = "hosted-judgment-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val DOER_TOOL = "search"
        const val BLOCKING_TOOL = "wait_to_be_cancelled"
        const val TOOL_RESULT = "found the retry policy in Uploader.kt"
        const val GOAL_TEXT = "Add a retry to the uploader"
        const val CLIP = 400
        const val OFF = "off"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )

        /**
         * The phases a recording run brackets: the six below with the one OBSERVE this
         * ticket adds, between EXECUTE and LEARN, which is where the question is asked.
         */
        val RECORDING_PHASES: List<String>
            get() = SIX_PHASES.dropLast(1) + CognitivePhase.OBSERVE.name + SIX_PHASES.last()

        /** The phases a one-cycle hosted run bracketed on its host seat before AMPR-421. */
        val SIX_PHASES: List<String> = listOf(
            CognitivePhase.PERCEIVE,
            CognitivePhase.RECALL,
            CognitivePhase.OBSERVE,
            CognitivePhase.PLAN,
            CognitivePhase.EXECUTE,
            CognitivePhase.LEARN,
        ).map { it.name }

        /** Two questions, so "all of a seat's questions in one request" is observable. */
        val VERIFIER_QUESTIONS: Map<String, Question> = linkedMapOf(
            "work_is_evidenced" to Question.Noul.of(
                instructions = "Does every step result name what it changed?",
                whenTrue = "Each result says what it did",
                whenFalse = "At least one result says only that it ran",
            ),
            "scope_held" to Question.Choice(
                instructions = "Did the run stay inside the goal?",
                criteria = linkedMapOf(
                    "inside" to "Everything done was asked for",
                    "outside" to "Something done was not asked for",
                ),
            ),
        )

        /** The three settlements of the last case, the reference one first. */
        val SETTLEMENTS: List<Settlement> = listOf(
            // The judge of the `Off` run is never called, which is itself asserted: were
            // it called, "off" is no declared answer key and the call would fail loudly.
            Settlement(label = OFF, answer = OFF, policy = RunPolicy()),
            Settlement(
                label = "answering true",
                answer = Question.Noul.TRUE_KEY,
                policy = RunPolicy(goalCheck = GoalCheck.Record),
            ),
            Settlement(
                label = "answering false",
                answer = Question.Noul.FALSE_KEY,
                policy = RunPolicy(goalCheck = GoalCheck.Record),
            ),
        )

        /** A gate that stops every run. */
        val StopAlways = object : PlanGate {
            override suspend fun review(plan: Plan): PlanDecision =
                PlanDecision.Stopped("a person said no")
        }

        /** A transport whose endpoint is down. */
        val BrokenDecisionClient = object : UpstreamDecisionClient {
            override suspend fun decide(
                request: DecisionRequest,
                configuration: AIConfiguration,
            ): DecisionResponse = throw IllegalStateException("decision endpoint down")
        }

        /** The golden state of [twoStepObservation]. */
        const val GOLDEN_STATE: String =
            "{\"goal\":\"Add a retry to the uploader\",\"cycle\":2,\"results\":[" +
                "{\"step\":\"Search for the retry policy\",\"status\":\"succeeded\"," +
                "\"result\":\"found the retry policy in Uploader.kt\"}," +
                "{\"step\":\"Change the uploader\",\"status\":\"failed\"," +
                "\"result\":\"the file was read-only\"}]}"

        /** Two steps, one of each outcome the question is meant to tell apart. */
        fun twoStepObservation(): RunObservation = RunObservation(
            runId = "run-under-test",
            goal = goal(GOAL_TEXT),
            cycle = 2,
            priorResults = listOf(
                succeeded("Search for the retry policy", TOOL_RESULT),
                failed("Change the uploader", "the file was read-only"),
            ),
        )

        fun succeeded(description: String, details: String): StepOutcome.Success = StepOutcome.Success(
            id = "outcome-$description",
            stepDescription = description,
            startTimestamp = FIXED_TIME,
            endTimestamp = FIXED_TIME,
            details = details,
        )

        fun failed(description: String, error: String): StepOutcome.Failure = StepOutcome.Failure(
            id = "outcome-$description",
            stepDescription = description,
            startTimestamp = FIXED_TIME,
            endTimestamp = FIXED_TIME,
            error = error,
        )

        /**
         * The step outcomes as three runs can be compared: the kind and the description.
         * Ids are minted and timestamps are read off the clock.
         */
        fun List<StepOutcome>.shape(): List<String> = map { "${it::class.simpleName}:${it.stepDescription}" }

        val FIXED_TIME: Instant = Instant.parse("2026-10-11T00:00:00Z")
    }
}
