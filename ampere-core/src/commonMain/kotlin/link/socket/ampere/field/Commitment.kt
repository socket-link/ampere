package link.socket.ampere.field

import kotlinx.datetime.Instant

/**
 * A Subject's undertaking to finish a piece of work by a date (AMPR-449, F31; AMPR-475, F36).
 *
 * Commitments come from assignment. A ticket that has a due date and is assigned to someone forms
 * one, held by the assignee. It stays [Status.OPEN] until the ticket is reassigned or moves to
 * done, and either of those resolves it. A ticket's Commitment is a fold of the two ticket events
 * and has no event of its own.
 *
 * Work that is not a ticket forms one too: a consumer announces the undertaking — a work session
 * the person planned, say, held by the Principal — with a `CommitmentEvent.Undertaken`, and ends
 * it with a `CommitmentEvent.Released` or a re-plan. Either way a Commitment is not a
 * [Primitive]: like a [Belief], it is a record *about* primitives (who committed, to what).
 *
 * Nothing here says whether a commitment was kept. Coordination state and Trust are derived from
 * a Subject's commitment history at read time (F32). They are never stored here, and this type
 * does not grow a `kept`, `late` or `trust` member.
 *
 * @property committer Who undertook the work: the ticket's assignee, whose value is the agent id
 *   (F30), or the committer an undertaking named.
 * @property ticketId The work the commitment is for: a ticket id, or a consumer's own work id
 *   announced by `CommitmentEvent.Undertaken`. The name predates undertakings and stays, because
 *   it is a stored column. A `String` rather than the `TicketId` alias, which lives outside this
 *   leaf package (the same trade as [Provenance.createdBy]).
 * @property goal The Goal the ticket's work is toward, when one is linked. An internal ticket
 *   has no goal link today, so this is `null` for every commitment the ledger forms. It is a
 *   member now so a later link does not change the shape.
 * @property due When the work is due: the ticket's due date when it was assigned, or the due date
 *   the undertaking named.
 * @property status Whether the commitment still binds; see [Status].
 * @property provenance The `TicketAssigned` or `CommitmentEvent.Undertaken` that formed it, at
 *   that event's `recorded_at`. Its `createdBy` is also the commitment's identity: one event forms
 *   at most one commitment.
 * @property resolvedBy The event that resolved it: a `TicketStatusChanged` to done, the
 *   `TicketAssigned` that handed the ticket to someone else, a `CommitmentEvent.Released`, or the
 *   `CommitmentEvent.Undertaken` that re-planned the work. `null` while [Status.OPEN].
 * @property resolvedAt That event's `recorded_at`, `null` while [Status.OPEN]. Kept on the row
 *   because the event store prunes, and resolution time is part of the history F32 reads.
 */
data class Commitment(
    val committer: Ref<Subject>,
    val ticketId: String,
    val goal: Ref<Goal>? = null,
    val due: Instant,
    val status: Status,
    val provenance: Provenance,
    val resolvedBy: String? = null,
    val resolvedAt: Instant? = null,
) {

    /** Where a [Commitment] stands. No `kept` or `broken`: judging that is F32's, at read time. */
    enum class Status {
        /** Formed and still binding. */
        OPEN,

        /** No longer binding: the work was done, released, re-planned, or passed to someone else. */
        RESOLVED,
    }
}
