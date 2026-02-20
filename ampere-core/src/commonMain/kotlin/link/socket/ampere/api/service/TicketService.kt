package link.socket.ampere.api.service

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketSummary
import link.socket.ampere.agents.events.tickets.TicketType

/**
 * SDK service for ticket creation, assignment, and status management.
 *
 * Maps to CLI commands: `ticket create`, `ticket assign`, `ticket status`
 */
interface TicketService {

    /**
     * Create a new ticket.
     *
     * ```
     * ampere.tickets.create(
     *     title = "Fix auth retry logic",
     *     description = "Transient failures cause immediate failure",
     *     priority = TicketPriority.HIGH,
     * )
     * ```
     */
    suspend fun create(
        title: String,
        description: String,
        priority: TicketPriority = TicketPriority.MEDIUM,
        type: TicketType = TicketType.TASK,
    ): Result<Ticket>

    /**
     * Assign a ticket to an agent.
     *
     * ```
     * ampere.tickets.assign(ticketId, "engineer-agent")
     * ```
     */
    suspend fun assign(ticketId: TicketId, agentId: AgentId?): Result<Unit>

    /**
     * Update a ticket's status.
     *
     * ```
     * ampere.tickets.transition(ticketId, TicketStatus.InProgress)
     * ```
     */
    suspend fun transition(ticketId: TicketId, status: TicketStatus): Result<Unit>

    /**
     * Get a ticket by ID.
     */
    suspend fun get(ticketId: TicketId): Result<Ticket>

    /**
     * List active tickets (not in Done status).
     */
    suspend fun list(): Result<List<TicketSummary>>
}
