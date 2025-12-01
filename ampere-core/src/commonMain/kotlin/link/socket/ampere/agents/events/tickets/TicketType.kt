package link.socket.ampere.agents.events.tickets

import kotlinx.serialization.Serializable

/**
 * Represents the type of work item.
 */
@Serializable
enum class TicketType {
    /** New functionality to be implemented. */
    FEATURE,

    /** Defect to be fixed. */
    BUG,

    /** General task or chore. */
    TASK,

    /** Research or investigation task. */
    SPIKE,
}
