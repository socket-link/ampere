package link.socket.ampere.agents.domain.concept.status

import kotlinx.serialization.Serializable

@Serializable
sealed interface EventStatus : Status {

    val name: String

    @Serializable
    data object Open : EventStatus {
        override val name: String = "Open"
        override val isClosed: Boolean = false
    }

    @Serializable
    data object WaitingForHuman : EventStatus {
        override val name: String = "Waiting For Human"
        override val isClosed: Boolean = false
    }

    @Serializable
    data object Resolved : EventStatus {
        override val name: String = "Resolved"
        override val isClosed: Boolean = true
    }

    // ** Validation function that checks if the status transition is valid. */
    fun canTransitionTo(newStatus: EventStatus): Boolean = when (this) {
        Open -> when (newStatus) {
            Open, WaitingForHuman, Resolved -> true
        }
        WaitingForHuman -> when (newStatus) {
            Open, WaitingForHuman, Resolved -> true
        }
        Resolved -> newStatus == Resolved // Resolved is the terminal state
    }

    companion object {
        fun fromName(name: String): EventStatus = when (name.lowercase()) {
            Open.name.lowercase() -> Open
            WaitingForHuman.name.lowercase() -> WaitingForHuman
            Resolved.name.lowercase() -> Resolved
            // Legacy support for old database entries
            "open" -> Open
            "waiting for human" -> WaitingForHuman
            "resolved" -> Resolved
            else -> throw IllegalArgumentException("Invalid event status: $name")
        }
    }
}
