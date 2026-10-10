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
 * Every claim comment for [identifier], release-blind.
 *
 * What is physically on the ticket, in the order the server assigned. Used where
 * the question is *did this instance ever claim this* — the release path's first
 * read — rather than *who holds it now*, which is [liveClaimsFor].
 *
 * Requires [this] in total order, as [LinearWorkSource.readComments] returns it.
 */
internal fun List<WorkSourceComment>.claimsFor(identifier: String): List<ClaimRecord> =
    mapNotNull { comment ->
        (SupervisoryComment.parse(comment.body) as? SupervisoryComment.Claim)
            ?.takeIf { it.issue == identifier }
            ?.let { ClaimRecord(it, comment.id, comment.createdAt) }
    }

/**
 * The claims on [identifier] that are still held: every claim no later
 * [SupervisoryComment.Release] by the same instance has retracted.
 *
 * ## Why the arbiter cannot just count claim comments
 *
 * Comments are append-only, so a claim cannot be deleted — and the arbiter crowns
 * the *earliest* one. A supervisor that died holding a ticket would therefore keep
 * winning every future race for it, and a reconciliation pass that put the ticket
 * back in the queue would have handed back a ticket nothing can ever claim again.
 * [SupervisoryComment.Release] is the retraction, and this is where it takes
 * effect: one pass over the total order, dropping an instance's claims when its
 * release goes by.
 *
 * Scoped per instance rather than per ticket, because a release says "*my* claim
 * is off" and must not retract a concurrent claimant's — the loser of a race
 * tidying up would otherwise free the winner's ticket out from under it.
 *
 * Requires [this] in total order, as [LinearWorkSource.readComments] returns it: a
 * release only retracts claims it *follows*, so an out-of-order list would retract
 * a re-claim the release predates.
 */
internal fun List<WorkSourceComment>.liveClaimsFor(identifier: String): List<ClaimRecord> {
    val live = mutableListOf<ClaimRecord>()
    forEach { comment ->
        when (val parsed = SupervisoryComment.parse(comment.body)) {
            is SupervisoryComment.Claim ->
                if (parsed.issue == identifier) {
                    live += ClaimRecord(parsed, comment.id, comment.createdAt)
                }

            is SupervisoryComment.Release ->
                if (parsed.issue == identifier) {
                    live.removeAll { it.instanceId == parsed.instanceId }
                }

            is SupervisoryComment.Escalation, null -> Unit
        }
    }
    return live
}

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

/**
 * How a claim release ended — the mirror of [ClaimOutcome], for the retraction a
 * reconciliation pass performs on behalf of a supervisor that died (AMPR-310).
 *
 * Four outcomes because a release has four honest endings, and a caller that
 * collapsed them would act wrongly on three: the claim was retracted and the ticket
 * requeued; it was retracted but the ticket left alone because someone else's claim
 * owns it; this instance never held it; or the work source showed interference and
 * the pass refused to write at all.
 */
sealed interface ReleaseOutcome {

    /** The issue the release was for, by identifier. */
    val issue: String

    /**
     * The claim is retracted. A later claimant will not see it in the total order.
     *
     * @property commentPosted False when a previous run of the pass had already
     *   posted the retraction and this run only finished the revert — the read-back
     *   that makes releasing a claim idempotent.
     * @property revertedTo The state the ticket was transitioned to, or null when
     *   no transition was written. Null is not a failure: see [reason].
     * @property reason Why no transition was written, when none was — the ticket was
     *   already queued, or another instance's claim still owns it.
     */
    data class Released(
        override val issue: String,
        val commentPosted: Boolean,
        val revertedTo: String? = null,
        val reason: String? = null,
    ) : ReleaseOutcome

    /**
     * No claim comment by that instance was ever posted for this issue.
     *
     * @property liveHolder The earliest instance whose claim *is* live, or null when
     *   nothing claims the ticket. Non-null means the ticket belongs to a
     *   supervisor that may still be working, and nothing about it may be moved.
     */
    data class NotHeld(
        override val issue: String,
        val liveHolder: SupervisorInstanceId? = null,
    ) : ReleaseOutcome

    /**
     * The claim was retracted where it could be, but the ticket's state was left
     * exactly as found because the work source showed interference — ratified rule
     * B4 of the AMPR-291 verdict.
     *
     * @property observedState Where the ticket actually is, and where it stays.
     */
    data class Deferred(
        override val issue: String,
        val commentPosted: Boolean,
        val observedState: String,
        val interference: ReleaseInterference,
    ) : ReleaseOutcome
}

/**
 * Why a reconciliation pass would not revert a ticket it holds the claim for.
 *
 * Deliberately a different type from [ClaimInterference], which answers a different
 * question. A losing claimant knows the state it set *and the state it set it from*,
 * because it read both; a reconciliation pass arrives after a crash with neither,
 * and has only the claim comment's timestamp and the state history to work with. One
 * type covering both would have to carry fields that are meaningless in half its
 * uses.
 */
sealed interface ReleaseInterference {

    /**
     * The ticket is in no state a supervisor's own dispatch transitions produce, so
     * something else moved it.
     *
     * This is the case B4 was written for. It also covers a ticket somebody
     * *finished*: a crashed dispatch whose ticket now reads Done had its work
     * accepted by someone, and requeueing it would reopen settled work.
     *
     * @property inFlight The states a dispatch legitimately sits in while claimed —
     *   [SupervisoryState.IN_FLIGHT].
     */
    data class MovedOutOfFlight(
        val observed: String,
        val inFlight: Set<String>,
    ) : ReleaseInterference

    /**
     * The ticket is in an in-flight state, but the span it is in began *before* this
     * claim was posted — so this claim is not what put it there.
     *
     * Either the ticket was already in progress when the claim landed, or something
     * moved it away and back. Either way the claim's transition is not the write
     * being undone, and reverting would overwrite a state something else set.
     *
     * @property since When the current span began, per the work source's own audit
     *   trail.
     * @property claimedAt The server's timestamp on the claim comment.
     */
    data class StateNotSetByClaim(
        val state: String,
        val since: Instant?,
        val claimedAt: Instant,
    ) : ReleaseInterference

    /**
     * The state history needed to judge the revert was not there to read.
     *
     * Deferring on absent evidence rather than reverting is the same choice B4 makes
     * about a detected move: with nothing to check, a revert is a blind write.
     */
    data class HistoryUnavailable(val reason: String) : ReleaseInterference
}

/**
 * Whether it is safe to revert the ticket [observed] to its queued state, given
 * that [claim] is the claim being retracted.
 *
 * Returns null when the evidence says this claim's own transition is the only thing
 * that put the ticket where it is — the one case where reverting writes over nothing
 * but the dead supervisor's own work.
 *
 * Two conditions, both from evidence the work source gives away for free: the ticket
 * is in a state a dispatch legitimately occupies, and the span it is in opened *after*
 * the claim comment was timestamped. The claim protocol posts the comment before the
 * transition, so a span this claim created is always strictly later than it; a span
 * that is earlier belongs to somebody else's write.
 */
internal fun detectReleaseInterference(
    observed: WorkSourceIssue,
    claim: ClaimRecord,
    inFlight: Set<String> = SupervisoryState.IN_FLIGHT,
): ReleaseInterference? {
    if (observed.statusName !in inFlight) {
        return ReleaseInterference.MovedOutOfFlight(observed = observed.statusName, inFlight = inFlight)
    }

    val spans = observed.stateHistory
        ?: return ReleaseInterference.HistoryUnavailable("the work source returned no state history")

    val current = spans.lastOrNull { it.endedAt == null }
        ?: return ReleaseInterference.HistoryUnavailable("the state history has no open span")

    if (current.stateName != observed.statusName) {
        return ReleaseInterference.HistoryUnavailable(
            "the open span is '${current.stateName}' but the issue reads '${observed.statusName}'",
        )
    }

    val since = current.startedAt
        ?: return ReleaseInterference.HistoryUnavailable("the open span carries no start time")

    return if (since < claim.createdAt) {
        ReleaseInterference.StateNotSetByClaim(current.stateName, since, claim.createdAt)
    } else {
        null
    }
}
