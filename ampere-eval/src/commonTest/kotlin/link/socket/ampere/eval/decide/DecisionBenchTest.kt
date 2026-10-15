package link.socket.ampere.eval.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.llm.decide.DecisionRequest
import link.socket.ampere.llm.decide.DecisionResponse
import link.socket.ampere.llm.decide.DeterministicDecisionClient
import link.socket.ampere.llm.decide.Judgment
import link.socket.ampere.llm.decide.ModelSnapshot
import link.socket.ampere.llm.decide.Question
import link.socket.ampere.llm.decide.UpstreamDecisionClient

/**
 * AMPR-422 task 1 validation: a ten-case toy suite with hand-computed expectations.
 *
 * Five met cases and five unmet, each state a single word so the fixture arms can answer by
 * reading it. The "graded" arm answers with a probability baked into the case id, so the
 * sweep and the operating point have hand-checkable inputs.
 */
class DecisionBenchTest {

    private val configuration: AIConfiguration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)
    private val question = Question.Noul.of("Is it done?", whenTrue = "done", whenFalse = "not done")

    /**
     * Ten cases. The state carries the probability a measured arm reports for `true`, so the
     * expected sweep can be computed by hand:
     *
     * | case | met | p(true) |
     * | -- | -- | -- |
     * | m1..m5 | yes | 0.95, 0.90, 0.80, 0.60, 0.40 |
     * | u1..u5 | no  | 0.10, 0.20, 0.30, 0.50, 0.70 |
     */
    private val cases = listOf(
        case("m1", met = true, p = 0.95, kind = "clean", family = "a"),
        case("m2", met = true, p = 0.90, kind = "clean", family = "a"),
        case("m3", met = true, p = 0.80, kind = "terse", family = "b"),
        case("m4", met = true, p = 0.60, kind = "terse", family = "b"),
        case("m5", met = true, p = 0.40, kind = "terse", family = "b"),
        case("u1", met = false, p = 0.10, kind = "partial", family = "a"),
        case("u2", met = false, p = 0.20, kind = "partial", family = "a"),
        case("u3", met = false, p = 0.30, kind = "empty_result", family = "b"),
        case("u4", met = false, p = 0.50, kind = "empty_result", family = "b"),
        case("u5", met = false, p = 0.70, kind = "empty_result", family = "b"),
    )

    private fun case(id: String, met: Boolean, p: Double, kind: String, family: String) = DecisionCase(
        id = id,
        kind = kind,
        state = "p=$p",
        question = question,
        expected = if (met) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY,
        family = family,
    )

    /** Reads `p=` off the state and answers with that probability of `true`. Measured. */
    private val graded = object : UpstreamDecisionClient {
        override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse {
            val p = request.state.removePrefix("p=").toDouble()
            val answer = if (p >= 0.5) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY
            return DecisionResponse(
                judgments = request.questions.mapValues {
                    Judgment(
                        answer = answer,
                        distribution = mapOf(Question.Noul.TRUE_KEY to p, Question.Noul.FALSE_KEY to 1 - p),
                        source = ConfidenceSource.MEASURED,
                        modelSnapshot = ModelSnapshot("test", "graded"),
                        locality = InferenceLocality.ON_DEVICE,
                    )
                },
                usage = TokenUsage(inputTokens = 10, outputTokens = 1, estimatedCost = 0.001),
            )
        }
    }

    /** Always says yes. Measured, one-hot — the shape of the any-success rule. */
    private val alwaysYes = DeterministicDecisionClient("always-yes") { _, _ -> Question.Noul.TRUE_KEY }

    /** Answers like [graded] but self-reports — the shape of the model-backed adapter. */
    private val selfReported = object : UpstreamDecisionClient {
        override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse {
            val p = request.state.removePrefix("p=").toDouble()
            return DecisionResponse(
                judgments = request.questions.mapValues {
                    Judgment(
                        answer = if (p >= 0.5) Question.Noul.TRUE_KEY else Question.Noul.FALSE_KEY,
                        distribution = null,
                        source = ConfidenceSource.SELF_REPORTED,
                        modelSnapshot = ModelSnapshot("test", "self-reported"),
                        locality = InferenceLocality.CLOUD,
                        confidence = 0.75,
                    )
                },
            )
        }
    }

    /** Throws on five of the ten cases: m1, m5, u1, u3 and u4. */
    private val flaky = object : UpstreamDecisionClient {
        private val down = setOf("p=0.95", "p=0.4", "p=0.1", "p=0.3", "p=0.5")

        override suspend fun decide(request: DecisionRequest, configuration: AIConfiguration): DecisionResponse {
            if (request.state in down) throw IllegalStateException("endpoint down")
            return graded.decide(request, configuration)
        }
    }

    @Test
    fun `the always-yes arm gets every unmet case wrong and every met case right`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("yes", alwaysYes, configuration)))

        val confusion = report.confusion("yes")
        assertEquals(5, confusion.metCalledMet)
        assertEquals(0, confusion.falseNotMet)
        assertEquals(5, confusion.falseMet)
        assertEquals(0, confusion.unmetCalledUnmet)
        assertEquals(1.0, confusion.falseMetRate)
        assertEquals(0.0, confusion.falseNotMetRate)
        assertEquals(0, confusion.missing)
    }

    @Test
    fun `the graded arm's confusion at its own answer is hand-computable`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("graded", graded, configuration)))

        // p >= 0.5 is "true": m1..m4 right, m5 wrong; u1..u3 right, u4 and u5 wrong.
        val confusion = report.confusion("graded")
        assertEquals(4, confusion.metCalledMet)
        assertEquals(1, confusion.falseNotMet)
        assertEquals(2, confusion.falseMet)
        assertEquals(3, confusion.unmetCalledUnmet)
        assertEquals(0.4, confusion.falseMetRate)
        assertEquals(0.2, confusion.falseNotMetRate)
    }

    @Test
    fun `a measured arm gets a 21-point sweep with hand-computed ends`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("graded", graded, configuration)))

        val sweep = assertNotNull(report.sweep("graded"))
        assertEquals(21, sweep.size)
        assertEquals(0.0, sweep.first().threshold)
        assertEquals(1.0, sweep.last().threshold)
        // At t = 0 everything is met: all five unmet are false met, no met is missed.
        assertEquals(5, sweep.first().falseMet)
        assertEquals(0, sweep.first().falseNotMet)
        // At t = 1 nothing reaches the bar: no false met, every met missed.
        assertEquals(0, sweep.last().falseMet)
        assertEquals(5, sweep.last().falseNotMet)
        // At t = 0.5: u4 (0.5) and u5 (0.7) are called met; m5 (0.4) is missed.
        val mid = sweep.first { it.threshold == 0.5 }
        assertEquals(2, mid.falseMet)
        assertEquals(1, mid.falseNotMet)
    }

    @Test
    fun `the zero-false-met operating point is the largest unmet probability`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("graded", graded, configuration)))

        val single = assertNotNull(report.zeroFalseMet("graded", DecisionBenchReport.Aggregate.SingleCall))
        // The highest unmet p is u5's 0.7; met cases above it are m1 (0.95), m2 (0.9), m3 (0.8).
        assertEquals(0.7, single.threshold)
        assertEquals(0.6, single.metRecognised)
        assertEquals(5, single.metSamples)
        assertEquals(5, single.unmetSamples)

        // With one repeat the mean of repeats is the single call.
        val mean = assertNotNull(report.zeroFalseMet("graded", DecisionBenchReport.Aggregate.MeanOfRepeats))
        assertEquals(single, mean)
    }

    @Test
    fun `a self-reported arm gets no sweep and no operating point`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("self", selfReported, configuration)))

        assertEquals(false, report.isFittable("self"))
        assertNull(report.sweep("self"))
        assertNull(report.zeroFalseMet("self", DecisionBenchReport.Aggregate.SingleCall))
        // Its confusion counts still exist: it answered.
        assertEquals(4, report.confusion("self").metCalledMet)
        assertTrue("No sweep" in report.render())
    }

    @Test
    fun `a one-hot deterministic arm is fittable and its operating point recognises nothing`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("yes", alwaysYes, configuration)))

        assertTrue(report.isFittable("yes"))
        val point = assertNotNull(report.zeroFalseMet("yes", DecisionBenchReport.Aggregate.SingleCall))
        assertEquals(1.0, point.threshold)
        assertEquals(0.0, point.metRecognised)
    }

    @Test
    fun `a throwing call is a record with an error and counts as not met`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("flaky", flaky, configuration)))

        val missing = report.missing("flaky")
        assertEquals(setOf("m1", "m5", "u1", "u3", "u4"), missing.map { it.caseId }.toSet())
        assertTrue(missing.all { it.error!!.contains("endpoint down") })
        val confusion = report.confusion("flaky")
        assertEquals(5, confusion.missing)
        // m1 and m5 went missing and read as not met; m2, m3, m4 answered true.
        assertEquals(2, confusion.falseNotMet)
        assertEquals(3, confusion.metCalledMet)
        // u1, u3, u4 went missing and read as not met; u2 answered false; u5 (0.7) answered true.
        assertEquals(4, confusion.unmetCalledUnmet)
        assertEquals(1, confusion.falseMet)
        // Fittability is over answered calls only.
        assertTrue(report.isFittable("flaky"))
    }

    @Test
    fun `repeats are recorded separately and stability reads across them`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("graded", graded, configuration)), repeats = 3)

        assertEquals(30, report.recordsOf("graded").size)
        assertEquals(setOf(0, 1, 2), report.recordsOf("graded").map { it.repeat }.toSet())
        val stability = report.stability("graded")
        assertEquals(10, stability.casesWithAllRepeats)
        assertEquals(10, stability.agreeingCases)
        assertEquals(1.0, stability.agreement)
        // Three repeats of the same answer: confusion counts triple.
        assertEquals(12, report.confusion("graded").metCalledMet)
    }

    @Test
    fun `per-kind and per-family tables partition the calls`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("graded", graded, configuration)))

        val byKind = report.byKind("graded")
        assertEquals(listOf("clean", "terse", "partial", "empty_result"), byKind.keys.toList())
        assertEquals(10, byKind.values.sumOf { it.total })
        assertEquals(2, byKind.getValue("empty_result").falseMet)
        assertEquals("empty_result", report.weakestKind("graded")?.first)

        val byFamily = report.byFamily("graded")
        assertEquals(setOf("a", "b"), byFamily.keys)
        assertEquals(4, byFamily.getValue("a").total)
    }

    @Test
    fun `latency and usage are read off the records`() = runTest {
        val report = DecisionBench().run(cases, listOf(DecisionArm("graded", graded, configuration)))

        val usage = report.usage("graded")
        assertEquals(10, usage.calls)
        assertEquals(100L, usage.inputTokens)
        assertEquals(10L, usage.outputTokens)
        assertEquals(0.01, usage.reportedCostUsd!!, 1e-9)
        assertNotNull(report.latency("graded"))
    }

    @Test
    fun `a playback client reproduces a recorded run and throws on a miss`() = runTest {
        val live = DecisionBench().run(cases, listOf(DecisionArm("graded", graded, configuration)), repeats = 2)

        val playback = PlaybackDecisionClient(live.records, armId = "graded")
        val replayed = DecisionBench().run(cases, listOf(DecisionArm("graded", playback, configuration)), repeats = 2)

        assertEquals(live.confusion("graded"), replayed.confusion("graded"))
        assertEquals(live.sweep("graded"), replayed.sweep("graded"))
        assertEquals(
            live.records.map { it.answer to it.distribution },
            replayed.records.map { it.answer to it.distribution },
        )

        // A third repeat was never recorded.
        val miss = DecisionBench().run(cases, listOf(DecisionArm("graded", playback, configuration)), repeats = 3)
        assertEquals(10, miss.missing("graded").size)
        assertTrue(miss.missing("graded").all { "PlaybackDecisionMiss" in it.error!! })

        // Through the seam, by digest: the recorded repeats are served in order, then it misses.
        val request = DecisionRequest(state = "p=0.95", questions = mapOf("q" to question))
        val byDigest = PlaybackDecisionClient(live.records, armId = "graded")
        assertEquals(Question.Noul.TRUE_KEY, byDigest.decide(request, configuration).judgments.getValue("q").answer)
        byDigest.decide(request, configuration)
        assertFailsWith<PlaybackDecisionMiss> { byDigest.decide(request, configuration) }
    }

    @Test
    fun `records round-trip through the one-per-line encoding`() = runTest {
        val live = DecisionBench().run(cases, listOf(DecisionArm("graded", graded, configuration)))

        val text = DecisionRecord.encodeLines(live.records)
        assertEquals(10, text.trimEnd().lines().size)
        assertEquals(live.records, DecisionRecord.decodeLines(text))
    }

    @Test
    fun `the choice form derives from the shipped noul and translates labels`() {
        val noul = cases.first()
        val choice = GoalMetForm.Choice.of(noul)
        assertEquals(GoalMetForm.MET, choice.expected)
        assertEquals(GoalMetForm.NOT_MET, GoalMetForm.Choice.of(cases.last()).expected)
        val question = choice.question as Question.Choice
        assertEquals(listOf(GoalMetForm.MET, GoalMetForm.NOT_MET, GoalMetForm.CANNOT_TELL), question.options)
        assertEquals(link.socket.ampere.propel.RunQuestions.goalMet.instructions, question.instructions)
        assertEquals(noul, GoalMetForm.Noul.of(choice).copy(question = noul.question))
    }

    @Test
    fun `the deterministic rules read the state's status words`() {
        fun state(vararg steps: Pair<String, String>): String = steps.joinToString(
            prefix = """{"goal":"g","cycle":1,"results":[""",
            postfix = "]}",
        ) { (status, result) -> """{"step":"a","status":"$status","origin":"external","result":"$result"}""" }
        val twoSucceeded = state("succeeded" to "x", "succeeded" to "y")
        val oneFailed = state("succeeded" to "x", "failed" to "boom")
        val allFailed = state("failed" to "boom")
        val nothing = state()

        assertEquals(listOf("succeeded", "failed"), GoalMetArms.statuses(oneFailed))
        val noul = GoalMetForm.Noul.question
        val choice = GoalMetForm.Choice.question

        assertEquals("true", GoalMetArms.answer(noul, GoalMetArms.statuses(twoSucceeded).any { it == "succeeded" }))
        assertEquals("met", GoalMetArms.answer(choice, met = true))
        assertEquals("not_met", GoalMetArms.answer(choice, met = false))
        // any-success: met on one failure beside a success; no-failure: not met there.
        assertEquals(true, GoalMetArms.statuses(oneFailed).any { it == "succeeded" })
        assertEquals(false, GoalMetArms.statuses(oneFailed).none { it == "failed" })
        // Neither rule calls an all-failed run met; no-failure calls an empty run met.
        assertEquals(false, GoalMetArms.statuses(allFailed).any { it == "succeeded" })
        assertEquals(true, GoalMetArms.statuses(nothing).none { it == "failed" })
    }
}
