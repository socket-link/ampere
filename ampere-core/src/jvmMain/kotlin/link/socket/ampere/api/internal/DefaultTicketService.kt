package link.socket.ampere.api.internal

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.tickets.TicketRepository
import link.socket.ampere.agents.events.tickets.TicketSummary
import link.socket.ampere.agents.events.tickets.TicketViewService
import link.socket.ampere.agents.service.TicketActionService
import link.socket.ampere.api.model.TicketFilter
import link.socket.ampere.api.service.TicketBuilder
import link.socket.ampere.api.service.TicketService

internal class DefaultTicketService(
    private val actionService: TicketActionService,
    private val viewService: TicketViewService,
    private val ticketRepository: TicketRepository,
) : TicketService {

    override suspend fun create(
        title: String,
        configure: (TicketBuilder.() -> Unit)?,
    ): Result<Ticket> {
        val builder = TicketBuilder()
        configure?.invoke(builder)
        return actionService.createTicket(
            title = title,
            description = builder.description,
            priority = builder.priority,
            type = builder.type,
        )
    }

    override suspend fun assign(ticketId: TicketId, agentId: AgentId?): Result<Unit> =
        actionService.assignTicket(ticketId, agentId)

    override suspend fun transition(ticketId: TicketId, status: TicketStatus): Result<Unit> =
        actionService.updateStatus(ticketId, status)

    override suspend fun get(ticketId: TicketId): Result<Ticket> =
        ticketRepository.getTicket(ticketId).map { ticket ->
            ticket ?: throw IllegalArgumentException("Ticket not found: $ticketId")
        }

    override suspend fun list(filter: TicketFilter?): Result<List<TicketSummary>> =
        viewService.listActiveTickets()
}
