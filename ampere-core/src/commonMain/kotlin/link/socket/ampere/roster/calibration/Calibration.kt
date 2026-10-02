package link.socket.ampere.roster.calibration

import kotlinx.serialization.Serializable

/**
 * What the calibration source knows about one [EstimateCategory] (AMPR-379).
 *
 * @property multiplier The ratio between what sessions actually took and what was
 *   estimated. `1.0` means the estimates were right, or that nothing is known yet —
 *   [samples] tells the two apart.
 * @property samples How many (estimate, actual) pairs the multiplier is drawn from.
 *   Zero means the multiplier is not earned; the Estimator says so rather than
 *   presenting it as evidence.
 */
@Serializable
data class Calibration(
    val multiplier: Double,
    val samples: Int,
) {
    companion object {
        /** The honest value when nothing has been measured. */
        val NONE: Calibration = Calibration(multiplier = 1.0, samples = 0)
    }
}
