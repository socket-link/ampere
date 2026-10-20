package link.socket.ampere.eval.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.eval.decide.PerceptionDriftCorpus.Kind

/**
 * AMPR-482 task 2: the two deterministic references, measured on the corpus and pinned (the
 * CI gate). Offline, in plain `jvmTest`.
 *
 * The expectations are not read off a run; they follow from what each kind renders:
 *
 * - **empty-only** answers bears whenever `changes` holds anything. Every bears kind adds one
 *   entry, so it is never false-settled. Of the seven beside kinds, three render the empty
 *   delta (`nothing_changed`, `cosmetic_reorder`, `own_run_actions_only`) and four render
 *   content — so its false-moved count is four kinds × eight families.
 * - **own-and-cosmetic** reads the same state. Production has already folded the run's own
 *   actions out and a re-read in another order to nothing, so its second and third clauses
 *   never fire on a production state, and its counts are empty-only's exactly.
 *
 * Both are one-hot, so both are fittable. The zero-false-settled operating point of a rule
 * that is never false-settled sits at `moveAbove = 1.0` — every bears call has `P(bears) = 1` —
 * and recognises as settled exactly the beside calls it answered settled: the empty triple.
 */
class PerceptionDriftDeterministicBenchTest {

    private val cases = PerceptionDriftCorpus.load()
    private val configuration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)
    private val arms = PerceptionDriftArms.all(PerceptionDriftCorpus.OWN_NAMES)
        .map { (id, client) -> DecisionArm(id, client, configuration) }

    private val families = PerceptionDriftCorpus.families.size
    private val bears = cases.count { Kind.of(it.kind).bears }
    private val beside = cases.size - bears
    private val renderEmpty = Kind.RENDER_EMPTY.size * families
    private val besideWithContent = beside - renderEmpty

    private fun report(form: PerceptionDriftForm): DecisionBenchReport = runBlocking {
        DecisionBench(PerceptionDriftBenchLive.QUESTION_ID).run(
            cases = cases.map(form::of),
            arms = arms,
            positiveKeys = form.settledKeys,
        )
    }

    @Test
    fun `empty-only is never false-settled and calls every beside case with content moved`() {
        val report = report(PerceptionDriftForm.Noul)

        val confusion = PerceptionDriftReport.confusion(report, PerceptionDriftArms.EMPTY_ONLY)
        assertEquals(0, confusion.falseSettled)
        assertEquals(besideWithContent, confusion.falseMoved)
        assertEquals(bears, confusion.bearsCalledBears)
        assertEquals(renderEmpty, confusion.settledCalledSettled)
        assertEquals(0, confusion.missing)
        assertEquals(cases.size, confusion.total)

        val byKind = PerceptionDriftReport.byKind(report, PerceptionDriftArms.EMPTY_ONLY)
        Kind.entries.forEach { kind ->
            val expected = when {
                kind.bears -> 0.0 to null
                kind in Kind.RENDER_EMPTY -> null to 0.0
                else -> null to 1.0
            }
            val actual = byKind.getValue(kind.wireName)
            assertEquals(expected, actual.falseSettledRate to actual.falseMovedRate, kind.wireName)
        }
    }

    @Test
    fun `own-and-cosmetic agrees with empty-only on every case`() {
        val report = report(PerceptionDriftForm.Noul)

        assertEquals(
            PerceptionDriftReport.confusion(report, PerceptionDriftArms.EMPTY_ONLY),
            PerceptionDriftReport.confusion(report, PerceptionDriftArms.OWN_AND_COSMETIC),
        )
        val byCase = report.records.groupBy { it.caseId }
        byCase.forEach { (caseId, records) ->
            assertEquals(1, records.map { it.answer }.toSet().size, "$caseId: both rules answer the same")
        }
        val weakest = report.weakestKind(PerceptionDriftArms.OWN_AND_COSMETIC)
        assertNotNull(weakest)
        assertTrue(Kind.of(weakest.first) !in Kind.RENDER_EMPTY && !Kind.of(weakest.first).bears, weakest.first)
        assertEquals(1.0, weakest.second)
    }

    @Test
    fun `both rules are fittable and their zero-false-settled point is moveAbove one`() {
        val report = report(PerceptionDriftForm.Noul)

        arms.map { it.id }.forEach { arm ->
            assertTrue(report.isFittable(arm), arm)
            DecisionBenchReport.Aggregate.entries.forEach { aggregate ->
                val point = PerceptionDriftReport.zeroFalseSettled(report, arm, aggregate)
                assertNotNull(point, "$arm $aggregate")
                assertEquals(1.0, point.moveAbove, "$arm $aggregate")
                assertEquals(renderEmpty.toDouble() / beside, point.settledRecognised, "$arm $aggregate")
                assertEquals(beside, point.besideSamples)
                assertEquals(bears, point.bearsSamples)
            }
            val sweep = assertNotNull(PerceptionDriftReport.sweep(report, arm), arm)
            assertEquals(21, sweep.size, arm)
            assertEquals(sweep.map { it.moveAbove }, sweep.map { it.moveAbove }.sorted(), "$arm: ascending")
            // At moveAbove 1.0 the rule "bears when P(bears) > 1.0" settles everything; below it, never.
            assertEquals(bears, sweep.last().falseSettled, "$arm: the top of the sweep settles every bears call")
            assertTrue(sweep.dropLast(1).all { it.falseSettled == 0 }, "$arm: never false-settled below 1.0")
        }
    }

    @Test
    fun `the choice form gives the rules the same counts and never answers cannot_tell`() {
        val noul = report(PerceptionDriftForm.Noul)
        val choice = report(PerceptionDriftForm.Choice)

        arms.map { it.id }.forEach { arm ->
            assertEquals(noul.confusion(arm), choice.confusion(arm), arm)
        }
        val answers = setOf(PerceptionDriftForm.BEARS, PerceptionDriftForm.BESIDE)
        assertTrue(choice.records.all { it.answer in answers })
        assertEquals(
            setOf(PerceptionDriftForm.BEARS, PerceptionDriftForm.CANNOT_TELL),
            PerceptionDriftForm.Choice.bearsKeys,
        )
        assertEquals(setOf(PerceptionDriftForm.BESIDE), PerceptionDriftForm.Choice.settledKeys)
    }

    @Test
    fun `the report renders in the bench's own words`() {
        val rendered = PerceptionDriftReport.render(report(PerceptionDriftForm.Noul), "Deterministic arms")

        assertTrue("False settled" in rendered && "False moved" in rendered, rendered)
        assertTrue("moveAbove" in rendered, rendered)
        assertTrue("False met" !in rendered, "the generic vocabulary does not leak: $rendered")
        assertTrue("## Arm `${PerceptionDriftArms.EMPTY_ONLY}`" in rendered, rendered)
    }
}
