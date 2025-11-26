package link.socket.ampere.agents.core.status

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.EventSource

@Serializable
sealed class TaskStatus : Status {

    @Serializable
    data object Pending : TaskStatus() {

        override val isClosed: Boolean = false
    }

    @Serializable
    data object InProgress : TaskStatus() {

        override val isClosed: Boolean = false
    }

    @Serializable
    data class Blocked(
        val reason: String,
    ) : TaskStatus() {

        override val isClosed: Boolean = false
    }

    @Serializable
    data class Completed(
        val completedAt: Instant,
        val completedBy: EventSource,
    ) : TaskStatus() {

        override val isClosed: Boolean = true
    }

    @Serializable
    data object Deferred : TaskStatus() {

        override val isClosed: Boolean = true
    }
}
