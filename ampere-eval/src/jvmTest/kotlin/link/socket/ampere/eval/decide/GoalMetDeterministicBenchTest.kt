package link.socket.ampere.eval.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.eval.decide.GoalMetCorpus.Kind

/**
 * AMPR-422 task 3: the two rules a run uses today, measured on the corpus and pinned (the CI
 * regression gate). Offline, in plain `jvmTest`.
 *
 * The expectations are not read off a run; they follow from what each kind contains:
 *
 * - **any-success** calls met whenever a step succeeded. Every met kind has one. Of the unmet
 *   kinds, only `all_failed` has none — so its false-met count is every unmet case that is not
 *   `all_failed`, and its false-not-met count is zero.
 * - **no-failure** calls met whenever no step failed. `redundant_failure` has one, so every
 *   such met case is a false not-met; the five unmet kinds with no failure are all false met.
 *
 * Both are one-hot, so both are fittable, and both operating points recognise nothing: the
 * loosest zero-false-met threshold on a rule that is confidently wrong is 1.0.
 */
class GoalMetDeterministicBenchTest {

    private val cases = GoalMetCorpus.load()
    private val configuration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

    private fun count(kind: Kind): Int = cases.count { it.kind == kind.wireName }
    private val unmet = cases.count { !Kind.of(it.kind).met }
    private val met = cases.size - unmet

    @Test
    fun `any-success calls every unmet case with a surviving step met`() = runBlocking {
        val report = DecisionBench().run(
            cases,
            listOf(DecisionArm(GoalMetArms.ANY_SUCCESS, GoalMetArms.anySuccess, configuration)),
        )

        val confusion = report.confusion(GoalMetArms.ANY_SUCCESS)
        assertEquals(unmet - count(Kind.ALL_FAILED), confusion.falseMet)
        assertEquals(0, confusion.falseNotMet)
        assertEquals(met, confusion.metCalledMet)
        assertEquals(count(Kind.ALL_FAILED), confusion.unmetCalledUnmet)
        assertEquals(0, confusion.missing)

        val byKind = report.byKind(GoalMetArms.ANY_SUCCESS)
        Kind.entries.filter { !it.met && it != Kind.ALL_FAILED }.forEach { kind ->
            assertEquals(1.0, byKind.getValue(kind.wireName).falseMetRate, kind.wireName)
        }
        assertEquals(0.0, byKind.getValue(Kind.ALL_FAILED.wireName).falseMetRate)
    }

    @Test
    fun `no-failure also misses every retried run`() = runBlocking {
        val report = DecisionBench().run(
            cases,
            listOf(DecisionArm(GoalMetArms.NO_FAILURE, GoalMetArms.noFailure, configuration)),
        )

        val confusion = report.confusion(GoalMetArms.NO_FAILURE)
        assertEquals(unmet - count(Kind.ALL_FAILED), confusion.falseMet)
        assertEquals(count(Kind.REDUNDANT_FAILURE), confusion.falseNotMet)
        assertEquals(met - count(Kind.REDUNDANT_FAILURE), confusion.metCalledMet)
        assertEquals(count(Kind.ALL_FAILED), confusion.unmetCalledUnmet)
        assertEquals(0, confusion.missing)
    }

    @Test
    fun `both rules are fittable and recognise nothing at zero false met`() = runBlocking {
        val report = DecisionBench().run(
            cases,
            listOf(
                DecisionArm(GoalMetArms.ANY_SUCCESS, GoalMetArms.anySuccess, configuration),
                DecisionArm(GoalMetArms.NO_FAILURE, GoalMetArms.noFailure, configuration),
            ),
        )

        GoalMetArms.all.keys.forEach { arm ->
            assertTrue(report.isFittable(arm), arm)
            val point = assertNotNull(report.zeroFalseMet(arm, DecisionBenchReport.Aggregate.SingleCall), arm)
            assertEquals(1.0, point.threshold, arm)
            assertEquals(0.0, point.metRecognised, arm)
            assertEquals(21, report.sweep(arm)?.size, arm)
        }
    }

    @Test
    fun `the choice form gives the rules the same counts`() = runBlocking {
        val choice = cases.map(GoalMetForm.Choice::of)
        val noulReport = DecisionBench().run(
            cases,
            listOf(DecisionArm(GoalMetArms.ANY_SUCCESS, GoalMetArms.anySuccess, configuration)),
            positiveKeys = GoalMetForm.Noul.positiveKeys,
        )
        val choiceReport = DecisionBench().run(
            choice,
            listOf(DecisionArm(GoalMetArms.ANY_SUCCESS, GoalMetArms.anySuccess, configuration)),
            positiveKeys = GoalMetForm.Choice.positiveKeys,
        )
        assertEquals(noulReport.confusion(GoalMetArms.ANY_SUCCESS), choiceReport.confusion(GoalMetArms.ANY_SUCCESS))
        assertTrue(choiceReport.records.all { it.answer == GoalMetForm.MET || it.answer == GoalMetForm.NOT_MET })
    }
}
