package link.socket.ampere.work.linear

import link.socket.ampere.agents.execution.dispatch.ClaimRegistry
import link.socket.ampere.agents.execution.dispatch.ClaimRelease
import link.socket.ampere.agents.execution.dispatch.ClaimScan
import link.socket.ampere.agents.execution.dispatch.ClaimedTicket
import link.socket.ampere.plug.spi.PerceivePredicate
import link.socket.ampere.plug.spi.PerceiveQuery

/**
 * Binds this work source to the startup reconciliation pass's
 * [ClaimRegistry] port (AMPR-310 step 4, over AMPR-305's adapter).
 *
 * All the judgement stays on this side of the port, which is the point of the port
 * being this narrow: which state name is the queued one, which states a dispatch
 * may legitimately sit in, and whether the state history shows a person intervened
 * are all provider vocabulary, and `ampere-core` can see none of it.
 *
 * Nothing here is new mechanism. [LinearWorkSource.release] is the release protocol;
 * this translates its outcome into the pass's vocabulary and nothing more.
 */
class LinearClaimRegistry(
    private val workSource: LinearWorkSource,
    private val queuedState: String = SupervisoryState.QUEUED_STATE,
    private val inFlight: Set<String> = SupervisoryState.IN_FLIGHT,
) : ClaimRegistry {

    override suspend fun releaseClaim(ticketId: String, heldBy: String): Result<ClaimRelease> {
        // The instance id comes off a journal written by another process, so it is
        // validated rather than trusted: a colon in it would have made the claim
        // comment it is supposed to match unparseable in the first place.
        val instance = runCatching { SupervisorInstanceId(heldBy) }
            .getOrElse { return Result.failure(it) }

        return workSource.release(
            issue = ticketId,
            heldBy = instance,
            queuedState = queuedState,
            inFlight = inFlight,
        ).map { outcome -> outcome.asClaimRelease() }
    }

    /**
     * Scan every in-flight state for tickets carrying a live claim comment.
     *
     * Costs one comment read per in-flight ticket, because a claim is a comment and
     * the query surface cannot filter on one. That is the price of the cold-start
     * path and the reason it is a fallback: the journal path reads one file.
     *
     * Only *live* claims count — a claim a previous pass already retracted is not
     * residue, and reporting it would make the degraded path raise the same ticket
     * on every startup forever.
     */
    override suspend fun claimedTickets(): Result<ClaimScan> {
        val tickets = mutableListOf<ClaimedTicket>()
        val incomplete = mutableListOf<String>()

        inFlight.sorted().forEach { state ->
            var cursor: String? = null
            var pages = 0

            while (true) {
                val query = PerceiveQuery(
                    linkId = workSource.linkId,
                    cursor = cursor,
                    predicates = listOf(PerceivePredicate.Equals(WorkSourceFields.STATE, state)),
                )
                val page = workSource.source.perceive(query).getOrElse { return Result.failure(it) }

                page.partialFailures.forEach { failure ->
                    incomplete += "an issue in '$state' could not be projected: $failure"
                }

                page.entities.forEach { issue ->
                    val comments = workSource.readComments(issue.identifier).getOrElse { error ->
                        incomplete += "could not read the comments of ${issue.identifier}: " +
                            (error.message ?: error.toString())
                        return@forEach
                    }
                    val holders = comments.liveClaimsFor(issue.identifier).sortedWith(CLAIM_ORDER)
                    if (holders.isNotEmpty()) {
                        tickets += ClaimedTicket(
                            ticketId = issue.identifier,
                            holders = holders.map { it.instanceId.value },
                            state = issue.statusName,
                        )
                    }
                }

                cursor = page.nextCursor ?: break
                if (++pages >= MAX_PAGES) {
                    incomplete += "the scan of '$state' stopped at $MAX_PAGES pages"
                    break
                }
            }
        }

        return Result.success(ClaimScan(tickets = tickets.sortedBy { it.ticketId }, incomplete = incomplete))
    }

    private fun ReleaseOutcome.asClaimRelease(): ClaimRelease = when (this) {
        is ReleaseOutcome.Released -> ClaimRelease.Released(
            commentPosted = commentPosted,
            revertedTo = revertedTo,
            reason = reason,
        )

        is ReleaseOutcome.NotHeld -> ClaimRelease.NotHeld(liveHolder = liveHolder?.value)

        is ReleaseOutcome.Deferred -> ClaimRelease.Deferred(
            observedState = observedState,
            reason = interference.describe(),
            commentPosted = commentPosted,
        )
    }

    /** The evidence, as one line a person reads in a reconciliation report. */
    private fun ReleaseInterference.describe(): String = when (this) {
        is ReleaseInterference.MovedOutOfFlight ->
            "'$observed' is not a state a dispatch sits in (${inFlight.sorted().joinToString()})"

        is ReleaseInterference.StateNotSetByClaim ->
            "'$state' began at $since, before the claim was posted at $claimedAt"

        is ReleaseInterference.HistoryUnavailable -> "no state-history evidence: $reason"
    }

    private companion object {

        /** Scans terminate; a work source that never stops paging is reported, not followed. */
        const val MAX_PAGES = 50
    }
}
