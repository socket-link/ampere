package link.socket.ampere.work.linear

import kotlinx.datetime.Instant

/**
 * One claim comment as the arbiter sees it: the parsed claim, plus the two
 * server-assigned facts that order it.
 *
 * @property createdAt The server's millisecond timestamp. The primary key of
 *   the total order, and the only thing on this work source that can decide a
 *   race.
 * @property commentId The tiebreak. Timestamps are millisecond-resolution, so
 *   two claims *can* collide; ordering by comment id after the timestamp makes
 *   the outcome a fact both racers compute identically rather than a coin flip
 *   each computes differently. Two claimants that disagreed about who won would
 *   both proceed, which is the one outcome this protocol exists to prevent.
 */
data class ClaimRecord(
    val claim: SupervisoryComment.Claim,
    val commentId: String,
    val createdAt: Instant,
) {
    val instanceId: SupervisorInstanceId get() = claim.instanceId
}

/** Earliest server timestamp first, comment id as tiebreak. */
internal val CLAIM_ORDER: Comparator<ClaimRecord> =
    compareBy<ClaimRecord>({ it.createdAt }, { it.commentId })

/**
 * Why a losing claimant refused to revert its own transition.
 *
 * Ratified rule B4 of the AMPR-291 verdict: the adapter reads state history
 * before any status revert, and if someone else moved the ticket it defers and
 * never overwrites. A revert is a *write to a field a human may have just set*,
 * and the cost of getting it wrong is asymmetric — an un-reverted ticket is
 * visible mess that a reconciliation pass cleans up, while a revert that stomps
 * a human's move destroys the record of an intervention.
 */
sealed interface ClaimInterference {

    /**
     * The ticket is no longer in the state this claimant set, so something moved
     * it afterwards and the revert target is stale.
     */
    data class MovedAway(
        val expected: String,
        val observed: String,
    ) : ClaimInterference

    /**
     * More state spans appeared than this claimant's own transition can account
     * for: at least one other transition landed in the window.
     */
    data class ExtraTransitions(
        val spansBefore: Int,
        val spansAfter: Int,
    ) : ClaimInterference

    /**
     * The work source did not return state history on one of the two reads, so
     * there is no evidence either way.
     *
     * Deferring on absent evidence rather than reverting is the same choice B4
     * makes about a detected move: with nothing to check, a revert is a blind
     * write.
     */
    data object HistoryUnavailable : ClaimInterference
}

/**
 * How a claim ended.
 *
 * Three outcomes, not two, because "we lost and tidied up" and "we lost and
 * would not touch it" are different situations for the caller: the second leaves
 * a ticket in a state this process put it in, and something — a reconciliation
 * pass, a human — has to know that.
 */
sealed interface ClaimOutcome {

    /** The issue the claim was for, by identifier. */
    val issue: String

    /**
     * This instance holds the claim: its claim comment is earliest in the
     * server's total order, and the ticket is in the claimed state.
     */
    data class Won(
        override val issue: String,
        val claim: ClaimRecord,
        val state: String,
    ) : ClaimOutcome

    /**
     * Another instance claimed first — or this instance could not prove it won —
     * and the ticket is back where it was found.
     *
     * @property winner The earliest claim, or null when the arbitration read
     *   returned no readable claim comment at all, not even this instance's own.
     *   Null is a loss, not a win: a claim this process cannot see in the total
     *   order is one it cannot show it holds, and proceeding on it is exactly the
     *   double-dispatch the protocol exists to prevent.
     */
    data class Lost(
        override val issue: String,
        val winner: ClaimRecord?,
        val revertedTo: String,
    ) : ClaimOutcome

    /**
     * Another instance claimed first, and this one left the ticket alone because
     * the work source showed interference.
     *
     * @property claimedState The state this instance transitioned the ticket to
     *   and did **not** revert. The ticket is still in it.
     */
    data class Deferred(
        override val issue: String,
        val winner: ClaimRecord?,
        val interference: ClaimInterference,
        val claimedState: String,
    ) : ClaimOutcome

    /** True only for [Won]. The one question a dispatcher has to ask. */
    val isWon: Boolean get() = this is Won
}

/**
 * Compares the state history either side of a transition to decide whether a
 * revert is safe.
 *
 * Returns null when this instance's own transition is the only thing that
 * happened — the one case where reverting writes over nothing but itself.
 *
 * A no-op transition (the ticket was already in the claimed state) adds no span,
 * so the span count is allowed to stay put as well as grow by one. Whether the
 * work source records a span for a same-state write is *not* verified, so both
 * are accepted rather than one asserted.
 */
internal fun detectInterference(
    before: WorkSourceIssue,
    after: WorkSourceIssue,
    claimedState: String,
): ClaimInterference? {
    if (after.statusName != claimedState) {
        return ClaimInterference.MovedAway(expected = claimedState, observed = after.statusName)
    }

    val spansBefore = before.stateHistory ?: return ClaimInterference.HistoryUnavailable
    val spansAfter = after.stateHistory ?: return ClaimInterference.HistoryUnavailable

    return if (spansAfter.size > spansBefore.size + 1) {
        ClaimInterference.ExtraTransitions(spansBefore.size, spansAfter.size)
    } else {
        null
    }
}
