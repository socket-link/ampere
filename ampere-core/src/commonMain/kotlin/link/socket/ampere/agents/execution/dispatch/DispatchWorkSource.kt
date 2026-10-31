package link.socket.ampere.agents.execution.dispatch

import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonWorkStatus

/**
 * The work-source operations the Switchboard may perform, and nothing more (AMPR-308).
 *
 * ### Why the trust boundaries live in this interface
 *
 * The Switchboard's three ratified trust boundaries are all *absences*: it never
 * authors a human-verdict comment, never removes a `gate:awaiting-verdict` label, and
 * never transitions a verdict-gated or `research`-labeled ticket to Done. An absence
 * is only enforceable if there is nothing to call. So the Switchboard holds this port
 * and not the work-source adapter: no method here removes a label, writes a raw
 * state, or posts a comment the caller composes in full. The adapter
 * (`ampere-work-linear`) depends on `ampere-core` and never the reverse, so the
 * Switchboard cannot reach the adapter's `removeLabels` or `markStatus` even by
 * accident. `DispatchWorkSourceSurfaceTest` pins the method set, so widening this
 * port is a visible, reviewed act rather than a quiet one.
 *
 * The writes that remain are each a protocol the adapter already ratified:
 * [claim] (comment-arbitrated, AMPR-305), [release] (the retraction AMPR-310 added),
 * [escalate] (`esc:` comment plus `gate:escalated`) and [requestVerdict] (the
 * review state plus `gate:awaiting-verdict` — a gate the Switchboard may *apply* and
 * never lift).
 *
 * ### Why reads come back as facts, not verdicts
 *
 * Unlike [ClaimRegistry], whose judgements are provider vocabulary, the questions
 * the Switchboard asks of a ticket are dispatch policy — is this blocker a research
 * ticket with a recorded verdict, does this scope overlap that one. Policy belongs
 * to the Switchboard, so the port hands back what the ticket says and the
 * Switchboard decides. Only the labels' spelling comes from the adapter, through
 * [conventions].
 */
interface DispatchWorkSource {

    /** The label vocabulary this work source writes, so the Switchboard never spells one itself. */
    val conventions: DispatchConventions

    /**
     * Tickets in [wave] that pass the work-source half of the ready rule: the wave
     * label, the queued state, no gate label, no open blocker.
     *
     * Every page, not one. A candidate the source could not evaluate comes back in
     * [CandidateScan.deferred] with its reason, never silently dropped.
     */
    suspend fun readyCandidates(wave: String): Result<CandidateScan>

    /**
     * Every ticket the work source shows as dispatched — claimed, in progress or in
     * review — by any supervisor or any person, with its description.
     *
     * The scope-overlap rule has to see work this process did not start: a ticket a
     * person dispatched by hand writes files as surely as one this Switchboard did.
     */
    suspend fun inFlight(): Result<InFlightScan>

    /** One ticket, freshly read, with its relations and description. */
    suspend fun ticket(ticketId: String): Result<DispatchTicket>

    /** Every comment on [ticketId], oldest first. */
    suspend fun comments(ticketId: String): Result<List<TicketComment>>

    /** Take [ticketId] for this supervisor instance through the claim protocol. */
    suspend fun claim(ticketId: String): Result<DispatchClaim>

    /**
     * Give back this instance's own claim on [ticketId]: retract it and return the
     * ticket to the queued state, unless the evidence says a person moved it.
     */
    suspend fun release(ticketId: String): Result<ClaimRelease>

    /**
     * Hand [ticketId] to a human: an `esc:` comment carrying [reason], plus the
     * escalation gate label. The ticket's state is left where it stopped.
     *
     * [reason] is the comment's body under a fixed `esc:` header, so nothing passed
     * here can open a comment the way a human verdict does — see [HumanVerdicts].
     */
    suspend fun escalate(ticketId: String, reason: String): Result<Unit>

    /**
     * Stop [ticketId] at a human-verdict gate: the review state plus
     * `gate:awaiting-verdict`. Applying the gate is a write this port allows;
     * lifting it is a verdict, and no method here can.
     */
    suspend fun requestVerdict(ticketId: String): Result<Unit>
}

/**
 * The labels a work source uses for the facts the Switchboard reads.
 *
 * @property wavePrefix Prefix of the wave-membership label, e.g. `wave:`.
 * @property gateLabels Every label that stops a ticket being dispatchable.
 * @property researchLabel The label marking a recon/spike ticket, whose deliverable
 *   is findings and a human verdict.
 * @property attachmentPrefix The work source's name as the context directory spells
 *   it, e.g. `linear` for `.context/attachments/linear-<uuid>/[LINEAR]-<id>.md`.
 */
data class DispatchConventions(
    val wavePrefix: String,
    val gateLabels: Set<String>,
    val researchLabel: String,
    val attachmentPrefix: String,
) {
    /** The label marking membership of wave [id]. */
    fun waveLabel(id: String): String = wavePrefix + id
}

/**
 * One ticket as the Switchboard reads it.
 *
 * @property status The canonical status. [CanonWorkStatus.TODO] is the queued state;
 *   [CanonWorkStatus.DONE] is the only status that satisfies the strict research
 *   blocker rule.
 * @property statusName The work source's own name for the state, for the context file.
 * @property description Null when the read did not carry one. A ticket with no
 *   description has no `ampere-scope` block, so it has unknown scope.
 * @property blockers Identifiers of the tickets blocking this one. **Null means "not
 *   read", not "none"** — the same rule the adapter's read model follows, because a
 *   list that defaulted to empty would let a ticket nobody checked pass the blocker
 *   rule.
 * @property branchName The branch name the work source suggests for this ticket.
 */
data class DispatchTicket(
    val identifier: String,
    val title: String,
    val status: CanonWorkStatus,
    val statusName: String,
    val labels: Set<String> = emptySet(),
    val description: String? = null,
    val blockers: List<String>? = null,
    val branchName: String? = null,
    val url: String? = null,
    val uuid: String? = null,
)

/** One comment on a ticket. */
data class TicketComment(
    val id: String,
    val body: String,
    val createdAt: Instant,
    val author: String? = null,
)

/**
 * One scan of the ready queue.
 *
 * @property deferred Candidates the source could not decide on. Not ready, and not
 *   silently absent either: "nothing is ready" and "we could not tell" are different
 *   answers to a supervisor deciding whether a wave is drained.
 * @property incomplete One entry per reason the scan may be short.
 */
data class CandidateScan(
    val tickets: List<DispatchTicket> = emptyList(),
    val deferred: List<DeferredTicket> = emptyList(),
    val incomplete: List<String> = emptyList(),
)

/** A candidate the work source could not evaluate, and why. */
data class DeferredTicket(
    val ticketId: String,
    val reason: String,
)

/**
 * The dispatched tickets the work source shows.
 *
 * @property incomplete Non-empty when the scan may have missed one. The Switchboard
 *   dispatches nothing on an incomplete scan: a ticket it could not see is a scope it
 *   could not check.
 */
data class InFlightScan(
    val tickets: List<DispatchTicket> = emptyList(),
    val incomplete: List<String> = emptyList(),
)

/** How a claim attempt ended. */
sealed interface DispatchClaim {

    /** This instance holds the ticket. */
    data object Won : DispatchClaim

    /**
     * Another claimant was first. The losing claim is already retracted.
     *
     * @property winner The instance that holds it, when the arbiter could read one.
     */
    data class Lost(val winner: String? = null) : DispatchClaim

    /**
     * The claim lost, and something moved the ticket in the window, so the loser
     * left the state alone rather than reverting it (rule B4 of the AMPR-291 verdict).
     */
    data class Deferred(val reason: String) : DispatchClaim
}
