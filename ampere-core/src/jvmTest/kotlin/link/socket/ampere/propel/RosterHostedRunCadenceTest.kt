package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProbeEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.StepOutcome
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
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.UpstreamDecisionClient
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-483 (N3, N4, N5, N9, N11): the loop re-enters at PERCEIVE on the receptor's verdict.
 *
 * Driven through the production wiring like [RosterHostedRunReceptorTest] — `rosterRunHost`
 * over real doors onto one in-memory database, the real phase services behind counting fakes —
 * with a Field that moves on every read, so each cycle's delta has something in it, and a
 * scripted decision transport on the host seat. The claims:
 *
 * - under `WhenDrifted(0.6)` a three-cycle run whose judge says *bears* after cycle 1 and
 *   *beside* after cycle 2 shows PERCEIVE brackets for cycles 1 and 2 only, `VerdictReached`
 *   `Violated` then `Holds`, the observed block on cycle 2's PLAN alone, and a second
 *   `KnowledgeRecalled` only under `WithPerceive`;
 * - under `EveryCycle` three PERCEIVE brackets and no judge;
 * - under `OncePerRun` one PERCEIVE, and the event stream is the pin from
 *   [RosterHostedRunFieldTest];
 * - a transport that throws re-perceives and leaves `Undetermined` on the record; a `Holds`
 *   leaves the run as it was, event for event;
 * - a run whose quota tripped does not re-perceive;
 * - `open` refuses `WhenDrifted` with no transport, with `FieldScope.Off`, or with no Field.
 *
 * AMPR-487 adds `WhenMoved` and the cycle on every bracket:
 *
 * - under `WhenMoved` a host seat with no decision transport re-perceives after every cycle in
 *   which the Field moved, leaves a `Violated` "moved: n changes" per cycle and no judgment, and
 *   every host bracket — and every seat's step bracket — carries the cycle it belongs to;
 * - a Field that never moves `Holds` every cycle and re-enters at OBSERVE;
 * - the lesson question goes in a request of its own beside it;
 * - `open` refuses `WhenMoved` only with `FieldScope.Off` or no Field.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual clock
 * walks straight past it.
 */
@OptIn(AmpereExperimentalApi::class)
class RosterHostedRunCadenceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    private val searched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    // ========================================================================
    // WhenDrifted
    // ========================================================================

    @Test
    fun `WhenDrifted re-perceives after a Violated and leaves a Holds cycle as it was`() = runBlocking<Unit> {
        val judge = ScriptedJudge(true, false, false)
        val host = hostSeat(decision = judge.client)
        val run = openRun(host, runHost = runHost(field = MovingField()), policy = drifted(0.6, cycles = 3))
        val result = run.execute()
        run.close()

        assertEquals(3, result.cyclesUsed)
        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(
            listOf(
                CognitivePhase.PERCEIVE, CognitivePhase.RECALL, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.PERCEIVE, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.LEARN,
            ),
            hostPhasesEntered(run.runId),
            "PERCEIVE for cycles 1 and 2 only; cycle 3 re-entered at OBSERVE",
        )
        assertEquals(
            listOf("verdict.violated", "verdict.holds", "verdict.holds"),
            verdicts(run.runId).map { it.verdict.kind() },
            "one verdict per executed cycle, through the host's door",
        )
        verdicts(run.runId).forEach { verdict ->
            assertEquals(ProbeId(DriftReceptor.PROBE_ID), verdict.probeId)
            assertEquals(run.runId, verdict.subjectId)
        }
        assertEquals(
            List(3) { RunQuestions.PERCEPTION_DRIFT_ID },
            judgments(run.runId).map { it.questionId },
            "the receptor's decide leaves the judgment record beside the verdict",
        )
        assertTrue(judgments(run.runId).all { it.cognitivePhase == CognitivePhase.OBSERVE })
        assertEquals(1, recalls(run.runId), "RECALL once under the default recall cadence")

        val plans = host.planPrompts()
        assertEquals(3, plans.size)
        assertFalse(plans[0].contains(OBSERVED_HEADING), "the first PLAN is what it was")
        assertTrue(plans[1].contains(OBSERVED_HEADING), "the drifted cycle's PLAN is shown what moved:\n${plans[1]}")
        assertTrue(plans[1].contains("- [external] LESSON: $BELIEF_TEXT 2"), plans[1])
        assertFalse(plans[2].contains(OBSERVED_HEADING), "a cycle that re-entered at OBSERVE is not")
        assertEquals(2, host.perceivePrompts().size, "two PERCEIVE model calls")

        // The baseline moves with a Holds: cycle 3's delta is what moved since the receptor
        // last looked (read 4, the after-cycle-2 snapshot), not since cycle 2's PERCEIVE.
        assertEquals(3, judge.requests.size)
        assertTrue(judge.requests[2].state.contains("$BELIEF_TEXT 5"), judge.requests[2].state)
        assertFalse(judge.requests[2].state.contains("$BELIEF_TEXT 4"), judge.requests[2].state)
        assertTrue(result.readExternalContent)
    }

    @Test
    fun `WithPerceive recalls again beside every re-run PERCEIVE`() = runBlocking<Unit> {
        val judge = ScriptedJudge(true, false, false)
        val host = hostSeat(decision = judge.client)
        val run = openRun(
            host,
            runHost = runHost(field = MovingField()),
            policy = drifted(0.6, cycles = 3).copy(
                cadence = PhaseCadence(
                    perceive = PerceiveCadence.WhenDrifted(0.6),
                    recall = RecallCadence.WithPerceive,
                ),
            ),
        )
        run.execute()
        run.close()

        assertEquals(2, recalls(run.runId), "one RECALL at open and one beside the re-perceive")
        assertEquals(
            listOf(
                CognitivePhase.PERCEIVE, CognitivePhase.RECALL, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.PERCEIVE, CognitivePhase.RECALL, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE, CognitivePhase.OBSERVE,
                CognitivePhase.LEARN,
            ),
            hostPhasesEntered(run.runId),
            "RECALL follows the re-run PERCEIVE and nothing else; the order never changes",
        )
        val (perception, recall) = assertIs<RosterHostedRun>(run).phaseCells
        assertEquals(2, assertNotNull(perception).cycle, "the cell holds the last PERCEIVE: cycle 2's")
        assertEquals(2, assertNotNull(recall).cycle)
        assertEquals(64, perception.inputDigest.length)
    }

    @Test
    fun `the lesson question rides in the receptor's request under WhenDrifted`() = runBlocking<Unit> {
        val judge = ScriptedJudge(false, false)
        val host = hostSeat(decision = judge.client)
        val run = openRun(
            host,
            runHost = runHost(field = MovingField()),
            policy = drifted(0.6, cycles = 2).copy(
                cadence = PhaseCadence(perceive = PerceiveCadence.WhenDrifted(0.6), learn = LearnCadence.Record),
            ),
        )
        run.execute()
        run.close()

        val records = judgments(run.runId)
        assertEquals(
            listOf(
                RunQuestions.PERCEPTION_DRIFT_ID,
                RunQuestions.LESSON_WORTH_KEEPING_ID,
                RunQuestions.PERCEPTION_DRIFT_ID,
                RunQuestions.LESSON_WORTH_KEEPING_ID,
            ),
            records.map { it.questionId },
        )
        records.chunked(2).forEach { (drift, lesson) ->
            assertEquals(drift.callId, lesson.callId, "one request for the receptor")
            assertEquals(drift.stateDigest, lesson.stateDigest, "one state for the receptor")
        }
        assertEquals(2, judge.requests.size)
        judge.requests.forEach { request ->
            assertEquals(
                setOf(RunQuestions.PERCEPTION_DRIFT_ID, RunQuestions.LESSON_WORTH_KEEPING_ID),
                request.questions.keys,
            )
        }
        assertEquals(listOf("verdict.holds", "verdict.holds"), verdicts(run.runId).map { it.verdict.kind() })
        assertEquals(1, host.perceivePrompts().size, "two Holds, no re-perceive")
    }

    @Test
    fun `a Holds every cycle is the observing run of before`() = runBlocking<Unit> {
        val reference = hostSeat(decision = ScriptedJudge(false, false).client)
        val asBefore =
            openRun(reference, runHost = runHost(field = MovingField()), policy = GOAL_POLICY.copy(cycles = 2))
        val expected = asBefore.execute()
        asBefore.close()

        val host = hostSeat(decision = ScriptedJudge(false, false).client)
        val run = openRun(host, runHost = runHost(field = MovingField()), policy = drifted(0.6, cycles = 2))
        val result = run.execute()
        run.close()

        assertEquals(hostPhasesEntered(asBefore.runId), hostPhasesEntered(run.runId), "the brackets are the same")
        assertEquals(runEvents(asBefore.runId), runEvents(run.runId), "and so is every event that is not a record")
        assertEquals(expected.status, result.status)
        assertEquals(expected.cyclesUsed, result.cyclesUsed)
        assertEquals(expected.stepOutcomes.size, result.stepOutcomes.size)
        assertEquals(reference.planPrompts(), host.planPrompts(), "the PLAN prompts are byte-identical")
        assertEquals(2, verdicts(run.runId).size, "the verdicts are the only addition")
    }

    @Test
    fun `a transport that throws re-perceives and leaves Undetermined on the record`() = runBlocking<Unit> {
        val host = hostSeat(decision = BrokenDecisionClient)
        val run = openRun(host, runHost = runHost(field = MovingField()), policy = drifted(0.6, cycles = 2))
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(2, result.cyclesUsed)
        assertTrue(judgments(run.runId).isEmpty(), "a call that threw records no judgment")
        val reached = verdicts(run.runId)
        assertEquals(2, reached.size, "one verdict per executed cycle, both undetermined")
        reached.forEach { event ->
            val verdict = assertIs<Verdict.Undetermined>(event.verdict)
            assertEquals(UndeterminedCause.EVIDENCE_UNREADABLE, verdict.cause)
        }
        assertEquals(2, host.perceivePrompts().size, "an unchecked cycle re-perceives")
        assertTrue(host.planPrompts()[1].contains(OBSERVED_HEADING), "and its PLAN is shown what moved")
    }

    // ========================================================================
    // EveryCycle and OncePerRun
    // ========================================================================

    @Test
    fun `EveryCycle re-perceives every cycle with no judge`() = runBlocking<Unit> {
        val judge = ScriptedJudge(true, true, true)
        val host = hostSeat(decision = judge.client)
        val run = openRun(
            host,
            runHost = runHost(field = MovingField()),
            policy = GOAL_POLICY.copy(cycles = 3, cadence = PhaseCadence(perceive = PerceiveCadence.EveryCycle)),
        )
        val result = run.execute()
        run.close()

        assertEquals(3, result.cyclesUsed)
        assertEquals(3, hostPhasesEntered(run.runId).count { it == CognitivePhase.PERCEIVE })
        assertEquals(3, host.perceivePrompts().size)
        assertTrue(judgments(run.runId).isEmpty(), "no judge")
        assertTrue(verdicts(run.runId).isEmpty(), "no verdict")
        assertTrue(judge.requests.isEmpty())
        val plans = host.planPrompts()
        assertFalse(plans[0].contains(OBSERVED_HEADING))
        assertTrue(plans[1].contains(OBSERVED_HEADING), "a run that observes shows the re-perceiving cycle what moved")
        assertTrue(plans[2].contains(OBSERVED_HEADING))
    }

    @Test
    fun `EveryCycle needs no transport and no Field`() = runBlocking<Unit> {
        val host = hostSeat(decision = null)
        val run = openRun(
            host,
            runHost = runHost(field = null),
            policy = RunPolicy(cycles = 3, cadence = PhaseCadence(perceive = PerceiveCadence.EveryCycle)),
        )
        val result = run.execute()
        run.close()

        assertEquals(3, result.cyclesUsed)
        assertEquals(
            listOf(
                CognitivePhase.PERCEIVE, CognitivePhase.RECALL, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE,
                CognitivePhase.PERCEIVE, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE,
                CognitivePhase.PERCEIVE, CognitivePhase.OBSERVE, CognitivePhase.PLAN,
                CognitivePhase.EXECUTE,
                CognitivePhase.LEARN,
            ),
            hostPhasesEntered(run.runId),
        )
        assertTrue(host.planPrompts().none { it.contains(OBSERVED_HEADING) }, "nothing observed, nothing shown")
    }

    @Test
    fun `OncePerRun with a transport on the seat is the pinned run`() = runBlocking<Unit> {
        val judge = ScriptedJudge(true, true)
        val host = hostSeat(decision = judge.client)
        val run = openRun(host, runHost = runHost(field = MovingField()), policy = RunPolicy(cycles = 2))
        run.execute()
        run.close()

        assertEquals(PINNED_TWO_CYCLE_HOST_PHASES, hostPhasesEntered(run.runId))
        assertEquals(PINNED_TWO_CYCLE_RUN_EVENTS, runEvents(run.runId))
        assertEquals(1, host.perceivePrompts().size)
        assertTrue(judgments(run.runId).isEmpty())
        assertTrue(verdicts(run.runId).isEmpty())
        assertTrue(judge.requests.isEmpty())
    }

    // ========================================================================
    // WhenMoved and the cycle on the bracket (AMPR-487)
    // ========================================================================

    @Test
    fun `WhenMoved re-perceives on a non-empty delta with no decision call`() = runBlocking<Unit> {
        val host = hostSeat(decision = null)
        val run = openRun(host, runHost = runHost(field = MovingField()), policy = moved(cycles = 3))
        val result = run.execute()
        run.close()

        assertEquals(3, result.cyclesUsed)
        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(
            listOf(
                CognitivePhase.PERCEIVE to 1, CognitivePhase.RECALL to 1, CognitivePhase.OBSERVE to 1,
                CognitivePhase.PLAN to 1, CognitivePhase.EXECUTE to 1, CognitivePhase.OBSERVE to 1,
                CognitivePhase.PERCEIVE to 2, CognitivePhase.OBSERVE to 2, CognitivePhase.PLAN to 2,
                CognitivePhase.EXECUTE to 2, CognitivePhase.OBSERVE to 2,
                CognitivePhase.PERCEIVE to 3, CognitivePhase.OBSERVE to 3, CognitivePhase.PLAN to 3,
                CognitivePhase.EXECUTE to 3, CognitivePhase.OBSERVE to 3,
                CognitivePhase.LEARN to 3,
            ),
            hostBracketsEntered(run.runId),
            "the Field moved after every cycle, so every later cycle re-entered at PERCEIVE, " +
                "and each bracket says which cycle it opened",
        )
        assertEquals(
            hostBracketsEntered(run.runId),
            hostBracketsExited(run.runId),
            "every PhaseExited carries the cycle its PhaseEntered did",
        )
        assertEquals(
            listOf(1, 2, 3),
            rows(run.runId)
                .filter { it.source_id == SCOUT_ID }
                .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
                .map { it.cycle },
            "a seat's step bracket carries the cycle its step ran in",
        )

        val reached = verdicts(run.runId)
        assertEquals(List(3) { "verdict.violated" }, reached.map { it.verdict.kind() })
        assertEquals(
            listOf("moved: 1 change since cycle 1", "moved: 1 change since cycle 2", "moved: 1 change since cycle 3"),
            reached.map { assertIs<Verdict.Violated>(it.verdict).reason },
        )
        reached.forEach { assertEquals(ProbeId(DriftReceptor.PROBE_ID), it.probeId) }
        assertTrue(judgments(run.runId).isEmpty(), "no judge, so no judgment on the record")
        assertEquals(3, host.perceivePrompts().size, "three PERCEIVE model calls")

        val plans = host.planPrompts()
        assertFalse(plans[0].contains(OBSERVED_HEADING))
        assertTrue(plans[1].contains(OBSERVED_HEADING), "a re-perceiving cycle's PLAN is shown what moved")
        assertTrue(plans[2].contains(OBSERVED_HEADING))

        // The payload carries the field, and a decoder reading it back gets the same value.
        val firstPerceive = rows(run.runId).first { it.source_id == HOST_ID && it.event_type == "PhaseEntered" }
        assertTrue(firstPerceive.payload.contains("\"cycle\":1"), firstPerceive.payload)
    }

    @Test
    fun `WhenMoved holds and re-enters at OBSERVE when nothing moved`() = runBlocking<Unit> {
        val host = hostSeat(decision = null)
        val run = openRun(host, runHost = runHost(field = StillField()), policy = moved(cycles = 2))
        val result = run.execute()
        run.close()

        assertEquals(2, result.cyclesUsed)
        assertEquals(
            listOf(
                CognitivePhase.PERCEIVE to 1, CognitivePhase.RECALL to 1, CognitivePhase.OBSERVE to 1,
                CognitivePhase.PLAN to 1, CognitivePhase.EXECUTE to 1, CognitivePhase.OBSERVE to 1,
                CognitivePhase.OBSERVE to 2, CognitivePhase.PLAN to 2,
                CognitivePhase.EXECUTE to 2, CognitivePhase.OBSERVE to 2,
                CognitivePhase.LEARN to 2,
            ),
            hostBracketsEntered(run.runId),
            "nothing moved, so no re-perceive",
        )
        assertEquals(
            listOf("nothing moved since cycle 1", "nothing moved since cycle 2"),
            verdicts(run.runId).map { assertIs<Verdict.Holds>(it.verdict).reason },
        )
        assertEquals(1, host.perceivePrompts().size)
        assertTrue(host.planPrompts().none { it.contains(OBSERVED_HEADING) })
        assertTrue(judgments(run.runId).isEmpty())
    }

    @Test
    fun `the lesson question goes in a request of its own under WhenMoved`() = runBlocking<Unit> {
        val judge = ScriptedJudge(true, true)
        val host = hostSeat(decision = judge.client)
        val run = openRun(
            host,
            runHost = runHost(field = MovingField()),
            policy = moved(cycles = 2).copy(
                cadence = PhaseCadence(perceive = PerceiveCadence.WhenMoved, learn = LearnCadence.Record),
            ),
        )
        run.execute()
        run.close()

        assertEquals(2, judge.requests.size, "one lesson request per executed cycle")
        judge.requests.forEach { request ->
            assertEquals(setOf(RunQuestions.LESSON_WORTH_KEEPING_ID), request.questions.keys, "drift is never asked")
        }
        assertEquals(
            listOf(RunQuestions.LESSON_WORTH_KEEPING_ID, RunQuestions.LESSON_WORTH_KEEPING_ID),
            judgments(run.runId).map { it.questionId },
        )
        assertEquals(listOf("verdict.violated", "verdict.violated"), verdicts(run.runId).map { it.verdict.kind() })
        assertEquals(2, host.perceivePrompts().size, "the verdict, not the judge, re-perceived cycle 2")
    }

    @Test
    fun `open refuses WhenMoved only with FieldScope Off or no Field`() = runBlocking<Unit> {
        val host = hostSeat(decision = null)
        val refusedScope = assertFailsWith<IllegalArgumentException> {
            openRun(
                host,
                runHost = runHost(field = MovingField()),
                policy = RunPolicy(cadence = PhaseCadence(perceive = PerceiveCadence.WhenMoved)),
            )
        }
        assertTrue(refusedScope.message!!.startsWith("PerceiveCadence.WhenMoved"), refusedScope.message)
        assertTrue(refusedScope.message!!.contains("FieldScope.Off"), refusedScope.message)

        val refusedField = assertFailsWith<IllegalArgumentException> {
            openRun(host, runHost = runHost(field = null), policy = moved(cycles = 2))
        }
        assertTrue(refusedField.message!!.startsWith("PerceiveCadence.WhenMoved"), refusedField.message)
        assertTrue(refusedField.message!!.contains("no FieldService"), refusedField.message)

        val refusedLesson = assertFailsWith<IllegalArgumentException> {
            openRun(
                host,
                runHost = runHost(field = MovingField()),
                policy = moved(cycles = 2).copy(
                    cadence = PhaseCadence(perceive = PerceiveCadence.WhenMoved, learn = LearnCadence.WhenLesson(0.5)),
                ),
            )
        }
        assertTrue(
            refusedLesson.message!!.startsWith("LearnCadence.WhenLesson asks the host seat"),
            "the lesson beside it still needs the transport: ${refusedLesson.message}",
        )
        assertEquals(0, host.transport.callCount, "no seat was built, so nothing perceived")

        // And with a Field and no transport it opens: WhenMoved needs no judge.
        val opened = openRun(host, runHost = runHost(field = MovingField()), policy = moved(cycles = 1))
        opened.close()
    }

    @Test
    fun `a bracket outside a hosted run encodes no cycle`() {
        val bracket = CognitivePhaseEvent.PhaseEntered(
            eventId = "event-1",
            timestamp = Instant.fromEpochSeconds(0),
            eventSource = EventSource.Agent(HOST_ID),
            agentId = HOST_ID,
            oldPhase = null,
            newPhase = CognitivePhase.PERCEIVE,
            nestingDepth = 0,
        )

        val text = DEFAULT_JSON.encodeToString(Event.serializer(), bracket)
        assertFalse(text.contains("cycle"), "a null cycle is not encoded, so the Arc path's payload is unchanged")
        assertEquals(bracket, DEFAULT_JSON.decodeFromString(Event.serializer(), text))

        val stamped = bracket.copy(cycle = 2)
        val stampedText = DEFAULT_JSON.encodeToString(Event.serializer(), stamped)
        assertTrue(stampedText.contains("\"cycle\":2"), stampedText)
        assertEquals(stamped, DEFAULT_JSON.decodeFromString(Event.serializer(), stampedText))
    }

    // ========================================================================
    // Quota
    // ========================================================================

    @Test
    fun `a run whose quota tripped does not re-perceive`() = runBlocking<Unit> {
        val host = hostSeat(decision = null)
        val run = openRun(
            host,
            runHost = runHost(field = null),
            policy = RunPolicy(
                cycles = 2,
                quotas = RunQuotas(steps = 1),
                cadence = PhaseCadence(perceive = PerceiveCadence.EveryCycle),
            ),
        )
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Partial, result.status, "step 1 ran, step 2 tripped the step quota")
        assertEquals(2, result.cyclesUsed)
        assertIs<StepOutcome.Success>(result.stepOutcomes[0])
        val tripped = assertIs<StepOutcome.Failure>(result.stepOutcomes[1])
        assertNotNull(tripped.tripped, "the second step tripped on the quota")
        assertEquals(1, hostPhasesEntered(run.runId).count { it == CognitivePhase.PERCEIVE }, "no re-perceive")
        assertEquals(1, host.perceivePrompts().size)
    }

    // ========================================================================
    // open refuses
    // ========================================================================

    @Test
    fun `open refuses WhenDrifted with no transport or with no Field`() = runBlocking<Unit> {
        val noTransport = hostSeat(decision = null)
        val refusedTransport = assertFailsWith<IllegalArgumentException> {
            openRun(noTransport, runHost = runHost(field = MovingField()), policy = drifted(0.6, cycles = 2))
        }
        assertTrue(
            refusedTransport.message!!.startsWith("PerceiveCadence.WhenDrifted asks the host seat"),
            refusedTransport.message,
        )
        assertTrue(refusedTransport.message!!.contains("upstreamDecisionClient"), refusedTransport.message)

        val host = hostSeat(decision = ScriptedJudge(true).client)
        val refusedScope = assertFailsWith<IllegalArgumentException> {
            openRun(
                host,
                runHost = runHost(field = MovingField()),
                policy = RunPolicy(cadence = PhaseCadence(perceive = PerceiveCadence.WhenDrifted(0.6))),
            )
        }
        assertTrue(refusedScope.message!!.contains("PerceiveCadence.WhenDrifted"), refusedScope.message)
        assertTrue(refusedScope.message!!.contains("FieldScope.Off"), refusedScope.message)

        val refusedField = assertFailsWith<IllegalArgumentException> {
            openRun(host, runHost = runHost(field = null), policy = drifted(0.6, cycles = 2))
        }
        assertTrue(refusedField.message!!.contains("no FieldService"), refusedField.message)

        assertEquals(0, host.transport.callCount, "no seat was built, so nothing perceived")
        assertTrue(
            door.database.eventStoreQueries.getAllEvents().executeAsList().none { it.source_id == HOST_ID },
            "and nothing was published under the host seat",
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
            val sequence = reads.toLong()
            return Result.success(
                Field(
                    viewer = viewer,
                    asOfSequence = sequence,
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
                            content = "$BELIEF_TEXT $read",
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

    /** A Field that never moves: the same subjects and the same one lesson on every read. */
    private class StillField : FieldService {
        private var reads = 0

        override suspend fun snapshot(viewer: Ref<Subject>?, beliefLimit: Int): Result<Field> {
            reads += 1
            val provenance = Provenance(createdBy = "event-still", createdAt = Instant.fromEpochSeconds(0))
            return Result.success(
                Field(
                    viewer = viewer,
                    asOfSequence = reads.toLong(),
                    subjects = listOf(
                        Subject.Agent(id = Ref(HOST_ID), name = "Host", provenance = provenance),
                        Subject.Agent(id = Ref(SCOUT_ID), name = "Scout", provenance = provenance),
                    ),
                    relationships = emptyList(),
                    goals = emptyList(),
                    beliefs = listOf(
                        Belief.Settled(
                            holder = Ref(HOST_ID),
                            subject = Ref<Goal>(GOAL_ID),
                            content = BELIEF_TEXT,
                            kind = BeliefKind.LESSON,
                            confidence = 0.9,
                            sensitivity = Sensitivity.PUBLIC,
                            provenance = provenance,
                        ),
                    ),
                    observations = emptyList(),
                ),
            )
        }
    }

    /**
     * A measured one-hot judge answering the drift question from a script, one answer per
     * request, and every other question `false`; keeps every request it was handed.
     */
    private class ScriptedJudge(vararg bears: Boolean) {
        val requests: MutableList<DecisionRequest> = CopyOnWriteArrayList()
        private val script = bears.toMutableList()

        private val inner = DeterministicDecisionClient(name = "scripted-judge") { _, question ->
            if (question === RunQuestions.perceptionDrift) {
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

        fun planPrompts(): List<String> = transport.prompts.filter { it.contains("planning module") }
    }

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

    private fun runHost(field: FieldService?): RunHost = rosterRunHost(
        createEventApi = door::doorFor,
        agentScope = scope,
        field = field,
    )

    private suspend fun openRun(host: Seat, runHost: RunHost, policy: RunPolicy): HostedRun = runHost.open(
        roster = roster(),
        seats = mapOf(HOST_ROLE to host.descriptor, SCOUT_ROLE to scoutSeat()),
        goal = goal(GOAL_TEXT, id = GOAL_ID),
        tools = tools(),
        policy = policy,
    )

    private fun drifted(moveAbove: Double, cycles: Int): RunPolicy = GOAL_POLICY.copy(
        cycles = cycles,
        cadence = PhaseCadence(perceive = PerceiveCadence.WhenDrifted(moveAbove)),
    )

    private fun moved(cycles: Int): RunPolicy = GOAL_POLICY.copy(
        cycles = cycles,
        cadence = PhaseCadence(perceive = PerceiveCadence.WhenMoved),
    )

    private fun searchStep(): String =
        planStep("Search the archive", SeatTool(SCOUT_ROLE, SEARCH_TOOL).id, SCOUT_ROLE.value)

    private fun judgments(runId: String): List<CognitiveEvent.JudgmentRecorded> =
        rows(runId).mapNotNull { decode(it) as? CognitiveEvent.JudgmentRecorded }

    private fun verdicts(runId: String): List<ProbeEvent.VerdictReached> =
        rows(runId).mapNotNull { decode(it) as? ProbeEvent.VerdictReached }

    private fun recalls(runId: String): Int =
        rows(runId).count { decode(it) is MemoryEvent.KnowledgeRecalled }

    /** The phases the host seat entered, in order. */
    private fun hostPhasesEntered(runId: String): List<CognitivePhase> =
        rows(runId)
            .filter { it.source_id == HOST_ID }
            .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
            .map { it.newPhase }

    /** The phases the host seat entered, in order, each with the cycle its bracket carries. */
    private fun hostBracketsEntered(runId: String): List<Pair<CognitivePhase, Int?>> =
        rows(runId)
            .filter { it.source_id == HOST_ID }
            .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseEntered }
            .map { it.newPhase to it.cycle }

    /** The phases the host seat exited, in order, each with the cycle its bracket carries. */
    private fun hostBracketsExited(runId: String): List<Pair<CognitivePhase, Int?>> =
        rows(runId)
            .filter { it.source_id == HOST_ID }
            .mapNotNull { decode(it) as? CognitivePhaseEvent.PhaseExited }
            .map { it.exitedPhase to it.cycle }

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

    private fun Verdict.kind(): String = when (this) {
        is Verdict.Holds -> "verdict.holds"
        is Verdict.Warn -> "verdict.warn"
        is Verdict.Violated -> "verdict.violated"
        is Verdict.Undetermined -> "verdict.undetermined"
    }

    private companion object {
        const val SDK_ID = "hosted-run-cadence-test-sdk"
        const val HOST_ID = "seat-host"
        const val SCOUT_ID = "seat-scout"
        const val SEARCH_TOOL = "search"
        const val SEARCH_RESULT = "found three earlier messages"
        const val GOAL_TEXT = "Answer the thread"
        const val GOAL_ID = "goal-ampr-483"
        const val MILESTONE_REACHED = "MilestoneReached"
        const val OBSERVED_HEADING = "Observed since the last cycle"
        const val BELIEF_TEXT = "the archive moved on read"

        val HOST_ROLE = RoleId("planner")
        val SCOUT_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
        val GOAL_POLICY = RunPolicy(observe = ObservePolicy(field = FieldScope.Goal))

        val BrokenDecisionClient = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse =
                throw IllegalStateException("decision endpoint down")
        }

        /** The host seat's brackets for a two-cycle run that observes and asks nothing: the FieldTest pin. */
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
