package link.socket.ampere.agents.execution.dispatch

/**
 * The work-source operations a startup reconciliation pass needs, and nothing more.
 *
 * ### Why the pass does not just hold a work-source adapter
 *
 * [StartupReconciler][link.socket.ampere.agents.execution.dispatch] lives in
 * `ampere-core` beside the journal and the process-group controller it consumes;
 * the work-source adapter (`ampere-work-linear`, AMPR-305) depends on `ampere-core`
 * and never the reverse. So the pass names the two questions it has to ask and the
 * adapter answers them.
 *
 * The split is not only a dependency trick. Both operations here are *judgements
 * about provider evidence* — which state is the queued one, whether the state
 * history shows a human intervened, whether a comment retracts a claim — and all
 * three of those facts are provider vocabulary. A port that returned raw comments
 * and states would move that judgement into a module that cannot see any of it.
 *
 * ### What it may and may not do
 *
 * Both methods are called with a *dead* supervisor's instance id, after its agent
 * process groups have been reaped and its local state repaired. Neither may ever
 * overwrite a state a person set: ratified rule B4 of the AMPR-291 verdict makes
 * [ClaimRelease.Deferred] the required answer when the evidence is ambiguous, and
 * an un-reverted ticket a visible mess a later pass can clean up.
 */
interface ClaimRegistry {

    /**
     * Retract the claim [heldBy] holds on [ticketId] and put the ticket back in the
     * queued state.
     *
     * Idempotent, and it has to be: the pass can be killed between the retraction
     * comment and the revert. Implementations read the ticket's comments back
     * before writing one, so a second run finds its own release comment, skips the
     * write, and still finishes the revert.
     *
     * @param heldBy The supervisor instance whose claim is being retracted —
     *   never this process's own.
     */
    suspend fun releaseClaim(ticketId: String, heldBy: String): Result<ClaimRelease>

    /**
     * Every ticket the work source shows as claimed, with the instances claiming it.
     *
     * The cold-start fallback, for a pass whose journal directory is empty. Strictly
     * weaker than the journal path: a claim comment says a supervisor took a ticket,
     * not that the supervisor is dead, so what comes back is *suspect* and the pass
     * reports it rather than acting on it unless told otherwise.
     */
    suspend fun claimedTickets(): Result<ClaimScan>
}

/**
 * What a claim scan found, and what it may have missed.
 *
 * The second half is not decoration. This scan is the only input the degraded path
 * has, so "nothing else is claimed" and "the work source went quiet partway through"
 * have to be different answers — a pass that reported the first when it meant the
 * second would look like it had recovered everything.
 *
 * @property incomplete One entry per reason the scan may be short: an issue that
 *   could not be decoded, a page cap reached, a comment read that failed. Empty
 *   means the scan saw everything the work source would show it.
 */
data class ClaimScan(
    val tickets: List<ClaimedTicket> = emptyList(),
    val incomplete: List<String> = emptyList(),
)

/** How a claim release ended. */
sealed interface ClaimRelease {

    /**
     * The claim is retracted.
     *
     * @property commentPosted False when a previous run of the pass had already
     *   posted the retraction — the read-back that makes this operation idempotent.
     * @property revertedTo The state the ticket now sits in, or null when the pass
     *   deliberately left it where it was. Null with [reason] filled in is not a
     *   failure: the ticket may already have been queued, or another instance's
     *   claim may still own it.
     * @property reason Why no transition was written, when none was.
     */
    data class Released(
        val commentPosted: Boolean,
        val revertedTo: String? = null,
        val reason: String? = null,
    ) : ClaimRelease

    /**
     * The instance named never claimed this ticket.
     *
     * @property liveHolder Another instance whose claim is still live, or null if
     *   nothing claims the ticket at all. Non-null means the ticket is not this
     *   pass's to move — a dead supervisor that lost a race leaves a claim comment
     *   behind, and the winner may still be working.
     */
    data class NotHeld(val liveHolder: String? = null) : ClaimRelease

    /**
     * Something moved the ticket after the claim, so the revert target is stale and
     * the pass wrote nothing (rule B4).
     *
     * @property observedState Where the ticket actually is now.
     * @property reason The evidence: a state the claim did not set, extra spans in
     *   the state history, or no history at all to check against.
     * @property commentPosted Whether the retraction comment was written anyway. It
     *   usually is: a dead supervisor's claim is unambiguously not held, and the
     *   comment is additive, so only the *state* is protected by rule B4.
     */
    data class Deferred(
        val observedState: String,
        val reason: String,
        val commentPosted: Boolean = false,
    ) : ClaimRelease
}

/**
 * One claimed ticket as the cold-start scan found it.
 *
 * @property holders Every instance with a live claim comment, earliest claim
 *   first. More than one means a race whose loser never tidied up.
 * @property state The work source's own name for the ticket's current state.
 */
data class ClaimedTicket(
    val ticketId: String,
    val holders: List<String>,
    val state: String,
)
