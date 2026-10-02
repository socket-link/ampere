package link.socket.ampere.standup

import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.canon.CanonId
import link.socket.ampere.roster.calibration.CalibratedEstimate

/**
 * What the Planner, Estimator and Scheduler converged on at a standup (AMPR-379):
 * the items still to do, their calibrated durations, the sessions proposed for
 * them, and the finish those sessions project.
 *
 * Graph work, 0W. The [rationale] is the *why* the Room attaches to every plan
 * change — each line names a fact the proposal rests on (a calibration with no
 * samples, an item with no estimate, windows that ran out).
 */
@Serializable
data class RevisionProposal(
    val revision: Int,
    val remaining: List<CanonId>,
    val estimates: List<CalibratedEstimate>,
    val sessions: List<ProposedSession>,
    val remainingWork: Duration,
    val projectedFinish: Instant?,
    val rationale: List<String> = emptyList(),
)

/** Something the standup could not settle and the human must. */
@Serializable
data class StandupEscalation(
    val subjectId: String,
    val reason: String,
    val threadId: MessageThreadId? = null,
)
