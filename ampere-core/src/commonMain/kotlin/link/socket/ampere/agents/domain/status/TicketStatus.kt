package link.socket.ampere.agents.domain.status

import kotlinx.serialization.Serializable
import link.socket.ampere.field.Admission
import link.socket.ampere.field.Rule
import link.socket.ampere.propel.Breaker

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
     *
     * Decided by the Breaker's `Rule.Transition` evaluation over [asTransitionRule], the
     * same one `Ticket.transitionTo` admits through, so the answer here and the admission
     * there cannot differ (AMPR-445).
     */
    fun canTransitionTo(newStatus: TicketStatus): Boolean =
        Breaker.admitTransition(asTransitionRule(), from = name, to = newStatus.name) == Admission.Pass

    companion object {
        val values: List<TicketStatus> by lazy { listOf(Backlog, Ready, InProgress, Blocked, InReview, Done) }

        private val transitionRule: Rule.Transition by lazy {
            Rule.Transition(
                allowed = values.associate { status ->
                    status.name to status.validTransitions().mapTo(linkedSetOf()) { it.name }
                },
            )
        }

        /**
         * The ticket lifecycle as a Hard `Rule.Transition` (F14, AMPR-445): every status,
         * by its [name], mapped to the names of [validTransitions]. [Done] maps to the empty
         * set rather than being left out, so the rule states that it is terminal.
         *
         * The labels are [name] — the form [fromString] reads back — because `field/`
         * cannot name this type. [canTransitionTo] and `Ticket.transitionTo` both admit a
         * change through `Breaker.admitTransition` with this rule, so [validTransitions] is
         * the only place the lifecycle is written down and the Breaker's evaluation is the
         * only place it is read.
         */
        fun asTransitionRule(): Rule.Transition = transitionRule

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
