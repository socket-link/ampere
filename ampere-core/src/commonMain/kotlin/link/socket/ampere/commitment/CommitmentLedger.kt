package link.socket.ampere.commitment

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.CommitmentEvent
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.db.Database
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref

/**
 * Folds the events that form and resolve Commitments (AMPR-449, F31; AMPR-475, F36).
 *
 * There is no Commitment event for tickets: a ticket's commitment is the fold of the ticket's
 * own events. An undertaking that is not a ticket — a work session the person planned, held by
 * the Principal — is announced by its own event, a [CommitmentEvent]. Either way the row is the
 * fold of the event it names, at that event's `recorded_at`, and the event is published before
 * the row exists.
 *
 * Every publisher of `TicketAssigned` and `TicketStatusChanged` calls this ledger right after
 * the door records the event. Today those are `TicketOrchestrator` and `TicketActionService`. A
 * commitment that only one path forms is the two-of-everything bug the Field exists to remove,
 * so a third publisher must call it too.
 *
 * - [assigned]: the assignee's commitment forms when the ticket has a due date. Any other
 *   holder's open commitment on the ticket is resolved by the same event.
 * - [statusChanged]: moving to [TicketStatus.Done] resolves every open commitment on the ticket.
 * - [undertaken]: the committer's commitment on a consumer's work id forms. Any other holder's
 *   open commitment on that work, and the committer's own at a different due date, is resolved
 *   by the same event.
 * - [released]: every open commitment on the work id is resolved.
 *
 * A consumer that announces an undertaking holds no environment, so it builds the ledger with
 * [over] on its database. It publishes the event through its `AgentEventApi` **first**, then
 * folds it with the id and the `recorded_at` the door returned — the pattern
 * `TicketActionService.assignTicket` follows for [assigned]:
 *
 * ```
 * val event = CommitmentEvent.Undertaken(
 *     eventId = generateUUID(workId),
 *     committer = SubjectRegistry.PRINCIPAL_REF.value,
 *     workId = workId,
 *     due = session.end,
 *     description = "Write the quarterly report",
 *     eventSource = EventSource.Human,
 *     timestamp = Clock.System.now(),
 * )
 * val recorded = eventApi.publish(event, runId = event.runId).getOrThrow()
 * CommitmentLedger.over(database).undertaken(event, recorded.recordedAt).getOrThrow()
 * ```
 *
 * A work id shares the ledger's key with ticket ids, so a ticket's events would fold onto a
 * consumer's commitment with the same id. Use an id no ticket can have.
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

        return resolveOpen(event.ticketId, Provenance(createdBy = event.eventId, createdAt = recordedAt))
    }

    /**
     * Fold a [CommitmentEvent.Undertaken]: [CommitmentEvent.Undertaken.committer] now owes
     * [CommitmentEvent.Undertaken.workId] by [CommitmentEvent.Undertaken.due].
     *
     * Every open commitment on the work held by someone else is resolved by [event], as
     * [assigned] resolves a previous holder's. So is the committer's own at a different due
     * date: a re-plan releases the old undertaking and forms the new one. The committer's own at
     * the same due date is kept, as a re-assignment to the same holder keeps it.
     *
     * An event already folded returns the row it formed, as it stands, and resolves nothing:
     * re-folding an old undertaking must not release a later re-plan.
     *
     * @param event The event as published.
     * @param recordedAt The `recorded_at` the door stamped on [event]'s envelope.
     * @return The commitment the committer holds on the work afterwards: open, unless [event]
     *   was folded before and has since been released.
     */
    suspend fun undertaken(
        event: CommitmentEvent.Undertaken,
        recordedAt: Instant,
    ): Result<Commitment> {
        store.get(event.eventId).getOrElse { return Result.failure(it) }?.let { return Result.success(it) }

        val provenance = Provenance(createdBy = event.eventId, createdAt = recordedAt)

        val open = store.forTicket(event.workId, Commitment.Status.OPEN).getOrElse { return Result.failure(it) }
        var kept: Commitment? = null
        for (commitment in open) {
            if (commitment.committer.value == event.committer && commitment.due == event.due) {
                kept = commitment
            } else {
                store.resolve(commitment.provenance.createdBy, provenance).onFailure { return Result.failure(it) }
            }
        }
        if (kept != null) return Result.success(kept)

        return store.insert(
            Commitment(
                committer = Ref(event.committer),
                ticketId = event.workId,
                goal = null,
                due = event.due,
                status = Commitment.Status.OPEN,
                provenance = provenance,
            ),
        )
    }

    /**
     * Fold a [CommitmentEvent.Released]: every open commitment on
     * [CommitmentEvent.Released.workId] is resolved by [event].
     *
     * @param event The event as published.
     * @param recordedAt The `recorded_at` the door stamped on [event]'s envelope.
     * @return The commitments [event] resolved.
     */
    suspend fun released(
        event: CommitmentEvent.Released,
        recordedAt: Instant,
    ): Result<List<Commitment>> =
        resolveOpen(event.workId, Provenance(createdBy = event.eventId, createdAt = recordedAt))

    private suspend fun resolveOpen(workId: String, resolution: Provenance): Result<List<Commitment>> {
        val open = store.forTicket(workId, Commitment.Status.OPEN).getOrElse { return Result.failure(it) }
        return Result.success(
            open.mapNotNull { commitment ->
                store.resolve(commitment.provenance.createdBy, resolution).getOrElse { return Result.failure(it) }
            },
        )
    }

    companion object {
        /**
         * A ledger over [database]'s `CommitmentStore` table: the one a consumer with no
         * environment builds to fold its own [CommitmentEvent]s.
         */
        fun over(database: Database): CommitmentLedger = CommitmentLedger(SqlDelightCommitmentStore(database))
    }
}
