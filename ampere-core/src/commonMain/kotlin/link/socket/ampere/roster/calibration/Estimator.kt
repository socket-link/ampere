package link.socket.ampere.roster.calibration

/**
 * The Estimator's Recall step: scale a baseline of estimates by what the
 * calibration source knows about each category (AMPR-379).
 *
 * Pure apart from the source reads, and 0W: calibration is arithmetic over recalled
 * samples, not a model call. The source is read once per distinct category in the
 * baseline, so a consumer backed by a store sees at most six reads per standup.
 */
object Estimator {

    suspend fun calibrate(
        baseline: List<WorkEstimate>,
        source: EstimateCalibrationSource,
    ): List<CalibratedEstimate> {
        val calibrations = baseline
            .map { it.category }
            .distinct()
            .associateWith { category -> source.multiplier(category) }

        return baseline.map { estimate ->
            CalibratedEstimate(
                estimate = estimate,
                calibration = calibrations.getValue(estimate.category),
            )
        }
    }
}
