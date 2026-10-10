package link.socket.ampere.llm.decide

import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.api.AmpereStableApi

/**
 * The check at the mouth of every band fit (AMPR-384, J5).
 *
 * A band is a threshold fitted over recorded confidences so a later judgment
 * can be read as act / middle / act-the-other-way. Fitting one needs values
 * that mean the same thing, and a probability a decision model measured and a
 * `high` a generative model said about itself do not: mixing them in one
 * column makes the column unusable. So no band is ever fitted on a
 * [ConfidenceSource.SELF_REPORTED] value, and a judgment with no distribution
 * has nothing to fit.
 *
 * W1 fits no band (the record's `band` is always null). This object exists so
 * that when W2's bands SPI (J10) arrives, the rule is already the one entry
 * point rather than a convention each fitter remembers.
 */
@AmpereStableApi
object BandFitGuard {

    /** True when [judgment] may contribute to a band fit. */
    fun isFittable(judgment: Judgment): Boolean =
        judgment.source == ConfidenceSource.MEASURED && judgment.distribution != null

    /** The one source a band may be fitted on. Throws [UnfittableConfidenceException] otherwise. */
    fun requireMeasured(source: ConfidenceSource) {
        if (source != ConfidenceSource.MEASURED) {
            throw UnfittableConfidenceException(
                "A band cannot be fitted on a $source confidence; only MEASURED values are fittable (J5).",
            )
        }
    }

    /**
     * [judgment]'s distribution, for a fitter. Throws [UnfittableConfidenceException]
     * when the judgment is self-reported or carries no distribution.
     */
    fun requireFittable(judgment: Judgment): Map<String, Double> {
        requireMeasured(judgment.source)
        return judgment.distribution
            ?: throw UnfittableConfidenceException(
                "A band cannot be fitted on a judgment with no distribution, " +
                    "even a measured one (${judgment.modelSnapshot.providerId}/${judgment.modelSnapshot.modelId}).",
            )
    }
}

/** Raised by [BandFitGuard] when a value that cannot be fitted reaches a band fit. */
@AmpereStableApi
class UnfittableConfidenceException(message: String) : IllegalArgumentException(message)
