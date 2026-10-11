package link.socket.ampere.agents.domain.knowledge

import link.socket.ampere.agents.domain.reasoning.Confidence
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource

/**
 * A confidence on its way into a belief row's `confidence` column (F10 as amended, AMPR-417).
 *
 * The column holds one number. What the number means depends on where it came from, and the
 * F10 mapping applies to one source only, so the write side takes this rather than a bare
 * `Double`:
 *
 * - [SelfReported] — a `low | medium | high` a generative model wrote about its own answer.
 *   Stored as the F10 mapping of the level ([Confidence.asProbability]: `0.25 / 0.5 / 0.75`).
 * - [Measured] — a probability a decision model returned (`Judgment.confidence` of a
 *   [ConfidenceSource.MEASURED] judgment). Stored exactly as given and never remapped.
 *
 * The source is not stored beside the number: `field.Belief` carries the already-mapped value
 * only, so a reader of the column cannot tell the two apart. Nothing fits a band on it for that
 * reason — bands are fitted on measured judgments, through `BandFitGuard`, never on this column.
 *
 * The number is [probability], never `value`: `ampere-core` is exported to Objective-C, and the
 * exporter lets two properties share a name only when one is declared in a final class or their
 * types bridge alike. A `value: Double` here — on an interface *or* this sealed (so non-final)
 * class — could share a subtype with `kotlinx.coroutines.StateFlow`, whose `value` is an object,
 * so the exporter renames `StateFlow`'s and every Swift caller reading a flow's `value` breaks.
 */
sealed class BeliefConfidence {

    /** The number written to the column, in `[0, 1]`. */
    abstract val probability: Double

    /** Where [probability] came from. */
    abstract val source: ConfidenceSource

    /** A level a generative model reported about itself, stored as its F10 mapping. */
    data class SelfReported(val level: Confidence) : BeliefConfidence() {
        override val probability: Double get() = level.asProbability()
        override val source: ConfidenceSource get() = ConfidenceSource.SELF_REPORTED
    }

    /** A probability a decision model measured, stored as-is. */
    data class Measured(override val probability: Double) : BeliefConfidence() {
        init {
            require(probability in 0.0..1.0) { "A measured confidence must be in [0, 1], was $probability" }
        }

        override val source: ConfidenceSource get() = ConfidenceSource.MEASURED
    }
}

/** The confidence [this] knowledge reported about itself, ready for the column; null if none. */
val Knowledge.reportedConfidence: BeliefConfidence?
    get() = confidence?.let { BeliefConfidence.SelfReported(it) }
