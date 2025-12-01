package link.socket.ampere.agents.events.tickets

import kotlinx.serialization.Serializable

/**
 * Represents the priority level of a ticket.
 */
@Serializable
enum class TicketPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}
