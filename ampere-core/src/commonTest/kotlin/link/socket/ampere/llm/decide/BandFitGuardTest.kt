package link.socket.ampere.llm.decide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.socket.ampere.agents.domain.reasoning.Confidence
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality

/** J5: no band is ever fitted on a self-reported value. */
class BandFitGuardTest {

    private val snapshot = ModelSnapshot(providerId = "test", modelId = "judge")

    private val measured = Judgment(
        answer = "true",
        distribution = mapOf("true" to 0.9, "false" to 0.1),
        source = ConfidenceSource.MEASURED,
        modelSnapshot = snapshot,
        locality = InferenceLocality.CLOUD,
    )
    private val selfReported = Judgment(
        answer = "true",
        distribution = null,
        source = ConfidenceSource.SELF_REPORTED,
        modelSnapshot = snapshot,
        locality = InferenceLocality.CLOUD,
        confidence = Confidence.HIGH.asProbability(),
    )

    @Test
    fun `a measured judgment with a distribution is fittable`() {
        assertTrue(BandFitGuard.isFittable(measured))
        assertEquals(measured.distribution, BandFitGuard.requireFittable(measured))
    }

    @Test
    fun `a self-reported judgment is refused`() {
        assertFalse(BandFitGuard.isFittable(selfReported))
        assertFailsWith<UnfittableConfidenceException> { BandFitGuard.requireFittable(selfReported) }
        assertFailsWith<UnfittableConfidenceException> { BandFitGuard.requireMeasured(ConfidenceSource.SELF_REPORTED) }
    }

    @Test
    fun `a measured judgment with no distribution has nothing to fit`() {
        val withoutDistribution = measured.copy(distribution = null, confidence = 0.6)

        assertFalse(BandFitGuard.isFittable(withoutDistribution))
        assertFailsWith<UnfittableConfidenceException> { BandFitGuard.requireFittable(withoutDistribution) }
    }
}
