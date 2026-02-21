package link.socket.ampere.api.service

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketSummary
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.api.model.TicketFilter

/**
 * SDK service for ticket creation, assignment, and status management.
 *
 * Maps to CLI commands: `ticket create`, `ticket assign`, `ticket status`
 *
 * ```
 * val ticket = ampere.tickets.create("Fix auth retry logic") {
 *     description("Transient failures cause immediate failure")
 *     priority(TicketPriority.HIGH)
 * }
 * ```
 */
interface TicketService {

    /**
     * Create a new ticket with optional configuration.
     *
     * ```
     * // Simple form:
     * ampere.tickets.create("Fix auth retry logic", "Transient failures cause immediate failure")
     *
     * // With builder DSL:
     * ampere.tickets.create("Fix auth retry logic") {
     *     description("Transient failures cause immediate failure")
     *     priority(TicketPriority.HIGH)
     *     type(TicketType.BUG)
     * }
     * ```
     *
     * @param title Brief summary of the work item
     * @param configure Optional builder for additional ticket properties
     */
    suspend fun create(
        title: String,
        configure: (TicketBuilder.() -> Unit)? = null,
    ): Result<Ticket>

    /**
     * Assign a ticket to an agent.
     *
     * ```
     * ampere.tickets.assign(ticketId, "engineer-agent")
     * ```
     *
     * @param ticketId The ticket to assign
     * @param agentId The agent to assign to, or null to unassign
     */
    suspend fun assign(ticketId: TicketId, agentId: AgentId?): Result<Unit>

    /**
     * Update a ticket's status.
     *
     * ```
     * ampere.tickets.transition(ticketId, TicketStatus.InProgress)
     * ```
     *
     * @param ticketId The ticket to update
     * @param status The new status
     */
    suspend fun transition(ticketId: TicketId, status: TicketStatus): Result<Unit>

    /**
     * Get a ticket by ID.
     *
     * ```
     * val ticket = ampere.tickets.get("ticket-123")
     * ```
     */
    suspend fun get(ticketId: TicketId): Result<Ticket>

    /**
     * List tickets, optionally filtered.
     *
     * ```
     * // All active tickets:
     * val tickets = ampere.tickets.list()
     *
     * // Only high priority bugs:
     * val bugs = ampere.tickets.list(TicketFilter(
     *     priority = TicketPriority.HIGH,
     *     type = TicketType.BUG,
     * ))
     * ```
     *
     * @param filter Optional filter criteria; null returns all active tickets
     */
    suspend fun list(filter: TicketFilter? = null): Result<List<TicketSummary>>
}

/**
 * Builder DSL for configuring a new ticket.
 */
class TicketBuilder {
    var description: String = ""
    var priority: TicketPriority = TicketPriority.MEDIUM
    var type: TicketType = TicketType.TASK

    /** Set the ticket description. */
    fun description(value: String) { description = value }

    /** Set the ticket priority. */
    fun priority(value: TicketPriority) { priority = value }

    /** Set the ticket type. */
    fun type(value: TicketType) { type = value }
}
