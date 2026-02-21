package link.socket.ampere.api.model

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType

/**
 * Filter criteria for listing tickets.
 *
 * All fields are optional. `null` means "no filter on this dimension".
 * Multiple non-null fields are combined with AND logic.
 */
data class TicketFilter(
    val status: TicketStatus? = null,
    val priority: TicketPriority? = null,
    val type: TicketType? = null,
    val assignedTo: AgentId? = null,
)
