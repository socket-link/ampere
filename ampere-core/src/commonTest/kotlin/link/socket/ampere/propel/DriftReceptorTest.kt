package link.socket.ampere.propel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.field.Belief
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.FieldDelta
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
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict

/**
 * AMPR-483 (N3): the drift receptor maps one measured judgment onto a `Verdict`.
 *
 * Driven over `AgentReasoning.create` with a decision client on the configuration and no
 * door, the way [link.socket.ampere.llm.decide.UpstreamDecisionClientPlumbingTest] drives
 * `decide`: the receptor's whole job is the mapping, and the record is the decision call's
 * own. Five outcomes, and the threshold read from the constructor:
 *
 * - an empty delta holds with no call; a delta of only `PROTECTED` rows is violated with no
 *   call;
 * - the judge's `p(true)` at or above `moveAbove` is violated, below it holds — the same
 *   judgment reads two ways under two thresholds, which is what "no constant" means;
 * - a self-reported answer and a transport that throws are both undetermined and unreadable;
 * - a cancellation is not a verdict.
 */
class DriftReceptorTest {

    @Test
    fun `an empty delta holds with no call`() = runTest {
        val judge = ScriptedJudge(pTrue = 1.0)
        val verdict = receptor(judge, moveAbove = 0.5).evaluate(drift(emptyDelta()))

        val holds = assertIs<Verdict.Holds>(verdict)
        assertEquals("nothing moved since cycle 2", holds.reason)
        assertEquals(0, judge.requests.size, "nothing moved, so nobody was asked")
    }

    @Test
    fun `a delta of only protected rows is violated with no call`() = runTest {
        val judge = ScriptedJudge(pTrue = 0.0)
        val delta = emptyDelta().copy(
            beliefsAdded = listOf(belief(SECRET, Sensitivity.PROTECTED)),
            observationsAdded = listOf(observation(SECRET, Sensitivity.PROTECTED)),
            protectedLeftOut = 2,
        )

        val verdict = receptor(judge, moveAbove = 0.5).evaluate(drift(delta))

        val violated = assertIs<Verdict.Violated>(verdict)
        assertTrue(violated.reason.startsWith("moved behind sensitivity: 2 protected entries"), violated.reason)
        assertTrue(!violated.reason.contains(SECRET), "nothing a protected row holds reaches the reason")
        assertEquals(0, judge.requests.size, "what moved cannot be shown to a judge, so none was asked")
    }

    @Test
    fun `a mixed delta with a protected row is still judged`() = runTest {
        val judge = ScriptedJudge(pTrue = 0.9)
        val delta = emptyDelta().copy(
            beliefsAdded = listOf(belief("the uploader retries twice"), belief(SECRET, Sensitivity.PROTECTED)),
            protectedLeftOut = 1,
        )

        val verdict = receptor(judge, moveAbove = 0.5).evaluate(drift(delta))

        assertIs<Verdict.Violated>(verdict)
        val request = judge.requests.single()
        assertTrue(!request.state.contains(SECRET), "the protected row is counted and never rendered: ${request.state}")
        assertTrue(request.state.contains("\"protected_left_out\":1"), request.state)
    }

    @Test
    fun `the judge's probability is read against the constructor's threshold`() = runTest {
        val judge = ScriptedJudge(pTrue = 0.55)
        val delta = emptyDelta().copy(beliefsAdded = listOf(belief("the uploader retries twice")))

        val convicted = receptor(judge, moveAbove = 0.5).evaluate(drift(delta))
        val acquitted = receptor(judge, moveAbove = 0.6).evaluate(drift(delta))
        val atThreshold = receptor(judge, moveAbove = 0.55).evaluate(drift(delta))

        val violated = assertIs<Verdict.Violated>(convicted)
        assertEquals("p(bears on goal) = 0.55, at or above 0.5", violated.reason)
        val holds = assertIs<Verdict.Holds>(acquitted)
        assertEquals("p(bears on goal) = 0.55, below 0.6", holds.reason)
        assertIs<Verdict.Violated>(atThreshold, "at the threshold is at or above it")
        assertEquals(3, judge.requests.size)
        judge.requests.forEach { request ->
            assertEquals(setOf(RunQuestions.PERCEPTION_DRIFT_ID), request.questions.keys)
            assertTrue(request.state.startsWith("{\"goal\":\"$GOAL_TEXT\",\"cycle\":2,"), request.state)
        }
    }

    @Test
    fun `a one-hot deterministic answer convicts or acquits outright`() = runTest {
        val delta = emptyDelta().copy(beliefsAdded = listOf(belief("the uploader retries twice")))
        val yes = DeterministicDecisionClient(name = "yes") { _, _ -> Question.Noul.TRUE_KEY }
        val no = DeterministicDecisionClient(name = "no") { _, _ -> Question.Noul.FALSE_KEY }

        assertIs<Verdict.Violated>(receptor(yes, moveAbove = 0.99).evaluate(drift(delta)))
        assertIs<Verdict.Holds>(receptor(no, moveAbove = 0.01).evaluate(drift(delta)))
    }

    @Test
    fun `a self-reported answer is undetermined and unreadable`() = runTest {
        val delta = emptyDelta().copy(beliefsAdded = listOf(belief("the uploader retries twice")))

        val verdict = receptor(SelfReportingJudge, moveAbove = 0.5).evaluate(drift(delta))

        val undetermined = assertIs<Verdict.Undetermined>(verdict)
        assertEquals(UndeterminedCause.EVIDENCE_UNREADABLE, undetermined.cause)
        assertTrue(undetermined.reason.startsWith("self-reported answer"), undetermined.reason)
    }

    @Test
    fun `a transport that throws is undetermined and unreadable`() = runTest {
        val delta = emptyDelta().copy(beliefsAdded = listOf(belief("the uploader retries twice")))

        val verdict = receptor(BrokenJudge, moveAbove = 0.5).evaluate(drift(delta))

        val undetermined = assertIs<Verdict.Undetermined>(verdict)
        assertEquals(UndeterminedCause.EVIDENCE_UNREADABLE, undetermined.cause)
        assertTrue(undetermined.reason.contains("decision endpoint down"), undetermined.reason)
    }

    @Test
    fun `a cancellation is not a verdict`() = runTest {
        val delta = emptyDelta().copy(beliefsAdded = listOf(belief("the uploader retries twice")))

        assertFailsWith<CancellationException> {
            receptor(CancellingJudge, moveAbove = 0.5).evaluate(drift(delta))
        }
    }

    @Test
    fun `the lesson question rides in the same request and its judgment is handed back`() = runTest {
        val judge = ScriptedJudge(pTrue = 0.2)
        val delta = emptyDelta().copy(beliefsAdded = listOf(belief("the uploader retries twice")))
        val receptor = receptor(
            judge,
            moveAbove = 0.5,
            alsoAsk = mapOf(RunQuestions.LESSON_WORTH_KEEPING_ID to RunQuestions.lessonWorthKeeping),
        )

        val verdict = receptor.evaluate(drift(delta))

        assertIs<Verdict.Holds>(verdict)
        assertEquals(
            setOf(RunQuestions.PERCEPTION_DRIFT_ID, RunQuestions.LESSON_WORTH_KEEPING_ID),
            judge.requests.single().questions.keys,
            "one request carrying both",
        )
        assertEquals(
            setOf(RunQuestions.PERCEPTION_DRIFT_ID, RunQuestions.LESSON_WORTH_KEEPING_ID),
            receptor.judgments.keys,
            "and both answers handed back",
        )

        receptor.evaluate(drift(emptyDelta()))
        assertTrue(receptor.judgments.isEmpty(), "an evaluation that made no call hands nothing back")
    }

    @Test
    fun `the receptor refuses a threshold outside the open interval and its own id beside it`() {
        val reasoning = reasoning(ScriptedJudge(pTrue = 0.5))

        assertEquals(ProbeId("perception-drift"), DriftReceptor(reasoning, moveAbove = 0.5, clip = 400).id)
        assertFailsWith<IllegalArgumentException> { DriftReceptor(reasoning, moveAbove = 0.0, clip = 400) }
        assertFailsWith<IllegalArgumentException> { DriftReceptor(reasoning, moveAbove = 1.0, clip = 400) }
        assertFailsWith<IllegalArgumentException> { DriftReceptor(reasoning, moveAbove = 0.5, clip = 0) }
        assertFailsWith<IllegalArgumentException> {
            DriftReceptor(
                reasoning,
                moveAbove = 0.5,
                clip = 400,
                alsoAsk = mapOf(RunQuestions.PERCEPTION_DRIFT_ID to RunQuestions.perceptionDrift),
            )
        }
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun receptor(
        client: UpstreamDecisionClient,
        moveAbove: Double,
        alsoAsk: Map<String, Question> = emptyMap(),
    ): DriftReceptor = DriftReceptor(reasoning(client), moveAbove = moveAbove, clip = CLIP, alsoAsk = alsoAsk)

    private fun reasoning(client: UpstreamDecisionClient): AgentReasoning = AgentReasoning.create(
        config = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = AI_CONFIGURATION,
            upstreamDecisionClient = client,
        ),
        actorId = "receptor-test",
    ) { agentRole = "Host" }

    private fun drift(delta: FieldDelta): RunDrift = RunDrift(
        runId = "run-ampr-483",
        goal = Task.Step(id = "goal-1", status = TaskStatus.Pending, description = GOAL_TEXT),
        cycle = 2,
        perceived = "the seats are declared",
        delta = delta,
        cycleResults = emptyList(),
        names = mapOf("seat-host" to "Host", "goal-1" to GOAL_TEXT),
    )

    private fun emptyDelta(): FieldDelta = noDrift().copy(fromSequence = 3, toSequence = 9)

    private fun belief(content: String, sensitivity: Sensitivity = Sensitivity.PUBLIC): Belief = Belief.Settled(
        holder = HOST,
        subject = ROOT_GOAL,
        content = content,
        kind = BeliefKind.LESSON,
        confidence = 0.9,
        sensitivity = sensitivity,
        provenance = Provenance(createdBy = "event-${content.hashCode()}", createdAt = Instant.fromEpochSeconds(0)),
    )

    private fun observation(content: String, sensitivity: Sensitivity): Belief.Settled = Belief.Settled(
        holder = HOST,
        subject = ROOT_GOAL,
        content = content,
        kind = BeliefKind.OBSERVATION,
        confidence = 1.0,
        sensitivity = sensitivity,
        provenance = Provenance(createdBy = "event-${content.hashCode()}", createdAt = Instant.fromEpochSeconds(0)),
    )

    /** A measured judge putting [pTrue] on `true` for every noul, remembering every request. */
    private class ScriptedJudge(private val pTrue: Double) : UpstreamDecisionClient {
        val requests = mutableListOf<DecisionRequest>()

        override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse {
            requests += request
            return DecisionResponse(
                judgments = request.questions.mapValues { (_, _) ->
                    Judgment(
                        answer = if (pTrue >= 0.5) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY,
                        distribution = mapOf(Question.Noul.TRUE_KEY to pTrue, Question.Noul.FALSE_KEY to 1.0 - pTrue),
                        source = ConfidenceSource.MEASURED,
                        modelSnapshot = SNAPSHOT,
                        locality = InferenceLocality.ON_DEVICE,
                    )
                },
            )
        }
    }

    private companion object {
        const val GOAL_TEXT = "Add a retry to the uploader"
        const val SECRET = "the thread's author lives at 12 Elm Street"
        const val CLIP = 400

        val HOST: Ref<Subject> = Ref("seat-host")
        val ROOT_GOAL: Ref<Goal> = Ref("goal-1")
        val SNAPSHOT = ModelSnapshot(providerId = "test", modelId = "scripted")
        val AI_CONFIGURATION = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

        /** Answers `true` with no distribution, as a generative model asked for JSON does. */
        val SelfReportingJudge = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse =
                DecisionResponse(
                    judgments = request.questions.mapValues {
                        Judgment(
                            answer = Question.Noul.TRUE_KEY,
                            distribution = null,
                            source = ConfidenceSource.SELF_REPORTED,
                            modelSnapshot = SNAPSHOT,
                            locality = InferenceLocality.CLOUD,
                            confidence = 0.75,
                        )
                    },
                )
        }

        val BrokenJudge = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse =
                throw IllegalStateException("decision endpoint down")
        }

        val CancellingJudge = object : UpstreamDecisionClient {
            override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse =
                throw CancellationException("the scope went away")
        }
    }
}
