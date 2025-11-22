package link.socket.kore.agents.events.tickets

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.kore.agents.core.AgentId

/** Type alias for ticket identifier. */
typealias TicketId = String

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

/**
 * Represents the current status of a ticket in its lifecycle.
 *
 * Valid state transitions:
 * - BACKLOG -> READY, DONE
 * - READY -> IN_PROGRESS
 * - IN_PROGRESS -> BLOCKED, IN_REVIEW, DONE
 * - BLOCKED -> IN_PROGRESS
 * - IN_REVIEW -> IN_PROGRESS, DONE
 */
@Serializable
enum class TicketStatus {
    /** Ticket is in the backlog, not yet prioritized for work. */
    BACKLOG,

    /** Ticket is ready to be picked up for work. */
    READY,

    /** Ticket is actively being worked on. */
    IN_PROGRESS,

    /** Ticket is blocked by external dependencies or issues. */
    BLOCKED,

    /** Ticket work is complete and awaiting review. */
    IN_REVIEW,

    /** Ticket is complete. */
    DONE,

    ;

    /**
     * Returns the set of valid statuses this status can transition to.
     */
    fun validTransitions(): Set<TicketStatus> = when (this) {
        BACKLOG -> setOf(READY, DONE)
        READY -> setOf(IN_PROGRESS)
        IN_PROGRESS -> setOf(BLOCKED, IN_REVIEW, DONE)
        BLOCKED -> setOf(IN_PROGRESS)
        IN_REVIEW -> setOf(IN_PROGRESS, DONE)
        DONE -> emptySet()
    }

    /**
     * Checks if transitioning to the given status is valid.
     */
    fun canTransitionTo(newStatus: TicketStatus): Boolean =
        newStatus in validTransitions()
}

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
        return copy(status = newStatus, updatedAt = updatedAt)
    }

    /**
     * Creates a copy of this ticket assigned to a new agent.
     *
     * @param agentId The ID of the agent to assign, or null to unassign.
     * @param updatedAt The timestamp of the assignment change.
     * @return A new Ticket with the updated assignment.
     */
    fun assignTo(agentId: AgentId?, updatedAt: Instant): Ticket =
        copy(assignedAgentId = agentId, updatedAt = updatedAt)

    /**
     * Checks if this ticket can transition to the given status.
     */
    fun canTransitionTo(newStatus: TicketStatus): Boolean =
        status.canTransitionTo(newStatus)

    /**
     * Returns true if the ticket is in a terminal state (DONE).
     */
    val isComplete: Boolean
        get() = status == TicketStatus.DONE

    /**
     * Returns true if the ticket is currently blocked.
     */
    val isBlocked: Boolean
        get() = status == TicketStatus.BLOCKED

    /**
     * Returns true if the ticket is actively being worked on.
     */
    val isInProgress: Boolean
        get() = status == TicketStatus.IN_PROGRESS

    /**
     * Returns true if the ticket is available to be picked up.
     */
    val isReady: Boolean
        get() = status == TicketStatus.READY
}
