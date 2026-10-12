package link.socket.ampere.commitment

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.db.Database
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref

/**
 * Folds the ticket events into Commitments (AMPR-449, F31).
 *
 * Every publisher of `TicketAssigned` and `TicketStatusChanged` calls this ledger right after
 * the door records the event. Today those are `TicketOrchestrator` and `TicketActionService`. A
 * commitment that only one path forms is the two-of-everything bug the Field exists to remove,
 * so a third publisher must call it too. There is no Commitment event: each row is the fold of
 * the event it names, at that event's `recorded_at`.
 *
 * - [assigned]: the assignee's commitment forms when the ticket has a due date. Any other
 *   holder's open commitment on the ticket is resolved by the same event.
 * - [statusChanged]: moving to [TicketStatus.Done] resolves every open commitment on the ticket.
 *
 * Folding the same event twice changes nothing. The forming event is the commitment's identity,
 * and a resolved row keeps its first resolution.
 */
class CommitmentLedger(
    private val store: CommitmentStore,
) {

    /**
     * Fold a `TicketAssigned`.
     *
     * Every open commitment on the ticket held by someone other than [TicketEvent.TicketAssigned.assignedTo]
     * is resolved by [event]; unassigning resolves them all. The new holder's commitment then
     * forms when [ticket] has a due date and is not done. Re-assigning a ticket to the Subject
     * that already holds it keeps the commitment it has, with that commitment's original due date.
     *
     * @param event The event as published.
     * @param recordedAt The `recorded_at` the door stamped on [event]'s envelope.
     * @param ticket The ticket as it stands after the assignment.
     * @return The open commitment the assignee holds afterwards, or `null` when there is none.
     */
    suspend fun assigned(
        event: TicketEvent.TicketAssigned,
        recordedAt: Instant,
        ticket: Ticket,
    ): Result<Commitment?> {
        val holder = event.assignedTo
        val provenance = Provenance(createdBy = event.eventId, createdAt = recordedAt)

        val open = store.forTicket(event.ticketId, Commitment.Status.OPEN).getOrElse { return Result.failure(it) }
        var kept: Commitment? = null
        for (commitment in open) {
            if (holder != null && commitment.committer.value == holder) {
                kept = commitment
            } else {
                store.resolve(commitment.provenance.createdBy, provenance).onFailure { return Result.failure(it) }
            }
        }
        if (kept != null) return Result.success(kept)

        val due = ticket.dueDate
        if (holder == null || due == null || ticket.status == TicketStatus.Done) return Result.success(null)

        return store.insert(
            Commitment(
                committer = Ref(holder),
                ticketId = event.ticketId,
                goal = null,
                due = due,
                status = Commitment.Status.OPEN,
                provenance = provenance,
            ),
        )
    }

    /**
     * Fold a `TicketStatusChanged`. A move to [TicketStatus.Done] resolves every open commitment
     * on the ticket. Any other move changes nothing.
     *
     * @param event The event as published.
     * @param recordedAt The `recorded_at` the door stamped on [event]'s envelope.
     * @return The commitments [event] resolved.
     */
    suspend fun statusChanged(
        event: TicketEvent.TicketStatusChanged,
        recordedAt: Instant,
    ): Result<List<Commitment>> {
        if (event.newStatus != TicketStatus.Done) return Result.success(emptyList())

        val resolution = Provenance(createdBy = event.eventId, createdAt = recordedAt)
        val open = store.forTicket(event.ticketId, Commitment.Status.OPEN).getOrElse { return Result.failure(it) }
        return Result.success(
            open.mapNotNull { commitment ->
                store.resolve(commitment.provenance.createdBy, resolution).getOrElse { return Result.failure(it) }
            },
        )
    }

    companion object {
        /** A ledger over [database]'s `CommitmentStore` table. */
        fun over(database: Database): CommitmentLedger = CommitmentLedger(SqlDelightCommitmentStore(database))
    }
}
