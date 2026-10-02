package link.socket.ampere.roster.calibration

/**
 * Where the Estimator's Recall step reads duration calibration from (AMPR-379).
 *
 * An Ampere-defined SPI that the consumer implements: Socket keeps the
 * (estimate, actual) pairs per user and per category in its own canon
 * (socket#1664) and answers this one question. The dependency direction is
 * Socket → Ampere only, so the storage, the sampling window, and what counts as
 * an "actual" are all the implementation's — Ampere reads a multiplier and a
 * sample count, nothing else.
 *
 * Implementations should be cheap and side-effect free: the Estimator reads one
 * value per category per standup.
 */
interface EstimateCalibrationSource {

    /** The calibration for [category]; [Calibration.NONE] when nothing is known. */
    suspend fun multiplier(category: EstimateCategory): Calibration
}

/**
 * The source that knows nothing: every category calibrates to `×1.0` with zero
 * samples. The default wherever a source is optional, and the right value for a
 * first run.
 */
object NoCalibration : EstimateCalibrationSource {

    override suspend fun multiplier(category: EstimateCategory): Calibration = Calibration.NONE
}
