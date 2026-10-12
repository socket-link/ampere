package link.socket.ampere.field

import kotlinx.datetime.Instant

/**
 * A Subject's undertaking to finish a piece of work by a date (AMPR-449, F31).
 *
 * Commitments come from assignment. A ticket that has a due date and is assigned to someone forms
 * one, held by the assignee. It stays [Status.OPEN] until the ticket is reassigned or moves to
 * done, and either of those resolves it. A Commitment is a fold of the two ticket events. It has
 * no event of its own, and it is not a [Primitive]: like a [Belief], it is a record *about*
 * primitives (who committed, to what).
 *
 * Nothing here says whether a commitment was kept. Coordination state and Trust are derived from
 * a Subject's commitment history at read time (F32). They are never stored here, and this type
 * does not grow a `kept`, `late` or `trust` member.
 *
 * @property committer Who undertook the work: the ticket's assignee. Its value is the agent id
 *   (F30).
 * @property ticketId The ticket the commitment is for. A `String` rather than the `TicketId`
 *   alias, which lives outside this leaf package (the same trade as [Provenance.createdBy]).
 * @property goal The Goal the ticket's work is toward, when one is linked. An internal ticket
 *   has no goal link today, so this is `null` for every commitment the ledger forms. It is a
 *   member now so a later link does not change the shape.
 * @property due When the work is due: the ticket's due date when it was assigned.
 * @property status Whether the commitment still binds; see [Status].
 * @property provenance The `TicketAssigned` event that formed it, at that event's `recorded_at`.
 *   Its `createdBy` is also the commitment's identity: one assignment forms at most one
 *   commitment.
 * @property resolvedBy The event that resolved it: a `TicketStatusChanged` to done, or the
 *   `TicketAssigned` that handed the ticket to someone else. `null` while [Status.OPEN].
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

        /** No longer binding: the work was done, or it passed to someone else. */
        RESOLVED,
    }
}
