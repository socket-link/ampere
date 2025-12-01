package link.socket.ampere.agents.events.tickets

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AgentId

/**
 * Service providing high-level views of ticket state.
 *
 * This transforms raw ticket data into the kind of summary information
 * that's useful for presentation: active tickets, ticket details with
 * acceptance criteria, assignment information, and status tracking.
 */
interface TicketViewService {
    /**
     * Get summary information for tickets that are actively being worked.
     * Active means tickets that are not in Done status.
     */
    suspend fun listActiveTickets(): Result<List<TicketSummary>>

    /**
     * Get complete details for a specific ticket.
     */
    suspend fun getTicketDetail(ticketId: TicketId): Result<TicketDetail>
}

/**
 * Summary information about a ticket for list displays.
 */
@Serializable
data class TicketSummary(
    val ticketId: TicketId,
    val title: String,
    val status: String,
    val assigneeId: AgentId?,
    val priority: String,
    val createdAt: Instant,
)

/**
 * Complete ticket details for detail views.
 */
@Serializable
data class TicketDetail(
    val ticketId: TicketId,
    val title: String,
    val description: String,
    val acceptanceCriteria: List<String>,
    val status: String,
    val assigneeId: AgentId?,
    val priority: String,
    val type: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val relatedThreadId: String?,
    val dueDate: Instant?,
    val createdByAgentId: AgentId,
)
