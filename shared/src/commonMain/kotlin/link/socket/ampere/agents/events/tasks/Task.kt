package link.socket.ampere.agents.events.tasks

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AssignedTo
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.tasks.Task.Status
import link.socket.ampere.agents.events.utils.generateUUID

typealias TaskId = String

@Serializable
sealed interface Task {

    val id: TaskId
    val status: Status

    data object Blank : Task {
        override val id: TaskId = generateUUID("")
        override val status: Status = Status.Pending()
    }

    @Serializable
    sealed class Status {

        abstract val isOpen: Boolean

        @Serializable
        data class Pending(
            val reason: String? = null,
        ) : Status() {
            override val isOpen: Boolean = true
        }

        @Serializable
        data object InProgress : Status() {
            override val isOpen: Boolean = true
        }

        @Serializable
        data class Blocked(
            val reason: String? = null,
        ) : Status() {
            override val isOpen: Boolean = false
        }

        @Serializable
        data class Completed(
            val completedAt: Instant? = null,
            val completedBy: EventSource? = null,
        ) : Status() {
            override val isOpen: Boolean = false
        }

        @Serializable
        data object Deferred : Status() {
            override val isOpen: Boolean = false
        }
    }

    companion object {
        val blank: Task = Blank
    }
}

/** A discrete topic or activity to discuss within a meeting agenda. */
@Serializable
data class AgendaItem(
    override val id: AgendaItemId,
    val topic: String,
    override val status: Status = Status.Pending(),
    val assignedTo: AssignedTo? = null,
) : Task

@Serializable
data class CodeChange(
    override val id: TaskId,
    val description: String,
    override val status: Status = Status.Pending(),
    val assignedTo: AssignedTo? = null,
) : Task
