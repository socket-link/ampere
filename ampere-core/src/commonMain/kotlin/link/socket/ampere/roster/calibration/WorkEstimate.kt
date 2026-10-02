package link.socket.ampere.roster.calibration

import kotlin.time.Duration
import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonId

/**
 * The Estimator's answer for one work item: how long, and what kind of time
 * (AMPR-379).
 *
 * A roster-side value rather than a field on `CanonWorkItem`: the canon's work
 * types are the intersection of what providers ship, none of which carries a
 * duration, and changing them is out of this ticket's scope. The item is named by
 * its [CanonId]; the estimate lives beside the graph, not inside it.
 */
@Serializable
data class WorkEstimate(
    val itemId: CanonId,
    val category: EstimateCategory,
    val duration: Duration,
)

/**
 * A [WorkEstimate] after the Recall step applied its category's [Calibration].
 *
 * Both halves are kept so the Room can show "2h, calibrated ×1.5 from 7 sessions"
 * rather than a bare 3h — the *why* attached to the number.
 */
@Serializable
data class CalibratedEstimate(
    val estimate: WorkEstimate,
    val calibration: Calibration,
) {
    val itemId: CanonId get() = estimate.itemId

    /** The duration the Scheduler plans with. */
    val duration: Duration get() = estimate.duration * calibration.multiplier
}
