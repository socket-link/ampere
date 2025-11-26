package link.socket.ampere.agents.events.tickets

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.status.TicketStatus

/** Type alias for ticket identifier. */
typealias TicketId = String

/**
 * Represents a work item managed by a Product Manager agent.
 *
 * Tickets are the fundamental unit of work in the system, representing tasks
 * that need to be completed by engineering agents. They follow a defined
 * lifecycle from creation through completion with proper state management.
 */
@Serializable
data class Ticket(
    /** Unique identifier for this ticket. */
    val id: TicketId,
    /** Brief summary of the work item. */
    val title: String,
    /** Detailed description of requirements and acceptance criteria. */
    val description: String,
    /** Category of work item. */
    val type: TicketType,
    /** Priority level for scheduling. */
    val priority: TicketPriority,
    /** Current lifecycle status. */
    val status: TicketStatus,
    /** Agent assigned to work on this ticket, null if unassigned. */
    val assignedAgentId: AgentId?,
    /** Agent that created this ticket. */
    val createdByAgentId: AgentId,
    /** Timestamp when the ticket was created. */
    val createdAt: Instant,
    /** Timestamp of the last modification. */
    val updatedAt: Instant,
    /** Optional due date for completion. */
    val dueDate: Instant? = null,
) {
    /**
     * Creates a copy of this ticket with an updated status, validating the transition.
     *
     * @param newStatus The target status to transition to.
     * @param updatedAt The timestamp of the status change.
     * @return A new Ticket with the updated status.
     * @throws IllegalStateException if the transition is not valid.
     */
    fun transitionTo(newStatus: TicketStatus, updatedAt: Instant): Ticket {
        require(status.canTransitionTo(newStatus)) {
            "Invalid state transition: cannot transition from $status to $newStatus. " +
                "Valid transitions from $status are: ${status.validTransitions()}"
        }
        return copy(
            status = newStatus,
            updatedAt = updatedAt,
        )
    }

    /**
     * Creates a copy of this ticket assigned to a new agent.
     *
     * @param agentId The ID of the agent to assign, or null to unassign.
     * @param updatedAt The timestamp of the assignment change.
     * @return A new Ticket with the updated assignment.
     */
    fun assignTo(
        agentId: AgentId?,
        updatedAt: Instant,
    ): Ticket =
        copy(
            assignedAgentId = agentId,
            updatedAt = updatedAt,
        )

    /**
     * Checks if this ticket can transition to the given status.
     */
    fun canTransitionTo(newStatus: TicketStatus): Boolean =
        status.canTransitionTo(newStatus)

    /**
     * Returns true if the ticket can be picked up.
     */
    val isReadyAndNotStarted: Boolean
        get() = status == TicketStatus.Ready

    /**
     * Returns true if the ticket is currently blocked.
     */
    val isBlocked: Boolean
        get() = status == TicketStatus.Blocked

    /**
     * Returns true if the ticket is actively being worked on.
     */
    val isInProgress: Boolean
        get() = status == TicketStatus.InProgress

    /**
     * Returns true if the ticket is in a terminal state (DONE).
     */
    val isComplete: Boolean
        get() = status == TicketStatus.Done

    companion object {
        val blank = Ticket(
            id = "",
            title = "",
            description = "",
            type = TicketType.TASK,
            priority = TicketPriority.LOW,
            status = TicketStatus.Backlog,
            assignedAgentId = null,
            createdByAgentId = "",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
            dueDate = null,
        )
    }
}
