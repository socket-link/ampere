package link.socket.ampere.eval.meter

import link.socket.ampere.eval.trace.Trace

/**
 * A [Meter] paired with a relative [weight] and a [required] flag.
 *
 * If [required] is `true`, a failed or missing reading from this meter causes the
 * [CompositeMeter] to fail immediately.
 */
data class WeightedMeter(
    val meter: Meter,
    val weight: Double,
    val required: Boolean = false,
)

/**
 * Aggregates child meters into a single weighted-mean [Reading].
 *
 * - Aggregate score = weighted mean of successful child scores.
 * - A required child that returns `Result.failure` propagates the failure immediately.
 * - A required child whose [Reading.passed] is `false` forces the composite to fail
 *   even if the weighted mean clears [tolerance].
 */
class CompositeMeter(
    val meterId: String,
    private val tolerance: Tolerance,
    private val children: List<WeightedMeter>,
) : Meter {

    override suspend fun measure(trace: Trace): Result<Reading> {
        val childReadings = mutableListOf<Pair<WeightedMeter, Reading>>()

        for (weighted in children) {
            val result = weighted.meter.measure(trace)
            when {
                result.isFailure && weighted.required -> return result
                result.isSuccess -> childReadings.add(weighted to result.getOrThrow())
            }
        }

        if (childReadings.isEmpty()) {
            return Result.failure(MeterError.NoReadings(meterId))
        }

        val totalWeight = childReadings.sumOf { (w, _) -> w.weight }
        val aggregateScore = if (totalWeight > 0) {
            childReadings.sumOf { (w, r) -> w.weight * r.score } / totalWeight
        } else {
            0.0
        }

        val anyRequiredFailed = childReadings.any { (w, r) -> w.required && !r.passed }
        val passed = !anyRequiredFailed && tolerance.passes(aggregateScore)

        return Result.success(
            Reading(score = aggregateScore, passed = passed, meterId = meterId),
        )
    }
}
