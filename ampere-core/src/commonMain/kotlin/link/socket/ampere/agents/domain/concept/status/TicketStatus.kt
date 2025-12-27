package link.socket.ampere.agents.domain.concept.status

import kotlinx.serialization.Serializable

/**
 * Represents the current status of a ticket in its lifecycle.
 *
 * Valid state transitions:
 * - Backlog -> Ready, Done
 * - Ready -> InProgress
 * - InProgress -> Blocked, InReview, Done
 * - Blocked -> InProgress
 * - InReview -> InProgress, Done
 */
@Serializable
sealed interface TicketStatus : Status {

    val name: String

    /** Ticket is in the backlog, not yet prioritized for work. */
    @Serializable
    data object Backlog : TicketStatus {
        override val name: String = "Backlog"
        override val isClosed: Boolean = false
    }

    /** Ticket is ready to be picked up for work. */
    @Serializable
    data object Ready : TicketStatus {
        override val name: String = "Ready"
        override val isClosed: Boolean = false
    }

    /** Ticket is actively being worked on. */
    @Serializable
    data object InProgress : TicketStatus {
        override val name: String = "In Progress"
        override val isClosed: Boolean = false
    }

    /** Ticket is blocked by external dependencies or issues. */
    @Serializable
    data object Blocked : TicketStatus {
        override val name: String = "Blocked"
        override val isClosed: Boolean = false
    }

    /** Ticket work is complete and awaiting review. */
    @Serializable
    data object InReview : TicketStatus {
        override val name: String = "In Review"
        override val isClosed: Boolean = false
    }

    /** Ticket is complete. */
    @Serializable
    data object Done : TicketStatus {
        override val name: String = "Done"
        override val isClosed: Boolean = true
    }

    /**
     * Returns the set of valid statuses this status can transition to.
     */
    fun validTransitions(): Set<TicketStatus> = when (this) {
        is Backlog -> setOf(Ready, Done)
        is Ready -> setOf(InProgress)
        is InProgress -> setOf(Blocked, InReview, Done)
        is Blocked -> setOf(InProgress)
        is InReview -> setOf(InProgress, Done)
        is Done -> emptySet()
    }

    /**
     * Checks if transitioning to the given status is valid.
     */
    fun canTransitionTo(newStatus: TicketStatus): Boolean =
        newStatus in validTransitions()

    companion object {
        val values: List<TicketStatus> by lazy { listOf(Backlog, Ready, InProgress, Blocked, InReview, Done) }

        fun fromString(name: String): TicketStatus = when (name) {
            "Backlog" -> Backlog
            "Ready" -> Ready
            "In Progress" -> InProgress
            "Blocked" -> Blocked
            "In Review" -> InReview
            "Done" -> Done
            else -> throw IllegalArgumentException("Invalid ticket status: $name")
        }
    }
}
