package link.socket.ampere.agents.domain.concept.task

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.status.TaskStatus

typealias TaskId = String

@Serializable
sealed interface Task {

    val id: TaskId
    val status: TaskStatus

    @Serializable
    data object Blank : Task {

        override val id: TaskId = ""
        override val status: TaskStatus = TaskStatus.Pending
    }

    @Serializable
    data class CodeChange(
        override val id: TaskId,
        override val status: TaskStatus,
        val description: String,
        val assignedTo: AssignedTo? = null,
    ) : Task

    companion object {

        val blank: Task = Blank
    }
}
