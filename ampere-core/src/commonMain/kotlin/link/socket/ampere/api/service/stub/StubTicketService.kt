package link.socket.ampere.api.service.stub

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.tickets.TicketSummary
import link.socket.ampere.api.model.TicketFilter
import link.socket.ampere.api.service.TicketBuilder
import link.socket.ampere.api.service.TicketService

/**
 * Stub implementation of [TicketService] for testing and parallel development.
 *
 * Creates real [Ticket] objects from provided parameters with generated IDs.
 */
class StubTicketService : TicketService {

    private var ticketCounter = 0

    override suspend fun create(
        title: String,
        configure: (TicketBuilder.() -> Unit)?,
    ): Result<Ticket> {
        ticketCounter++
        val builder = TicketBuilder()
        configure?.invoke(builder)
        val now = Clock.System.now()
        return Result.success(
            Ticket(
                id = "stub-ticket-$ticketCounter",
                title = title,
                description = builder.description,
                type = builder.type,
                priority = builder.priority,
                status = TicketStatus.Backlog,
                assignedAgentId = null,
                createdByAgentId = "stub",
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    override suspend fun assign(ticketId: TicketId, agentId: AgentId?): Result<Unit> =
        Result.success(Unit)

    override suspend fun transition(ticketId: TicketId, status: TicketStatus): Result<Unit> =
        Result.success(Unit)

    override suspend fun get(ticketId: TicketId): Result<Ticket> =
        Result.failure(NoSuchElementException("Stub: ticket not found: $ticketId"))

    override suspend fun list(filter: TicketFilter?): Result<List<TicketSummary>> =
        Result.success(emptyList())
}
