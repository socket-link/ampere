package link.socket.ampere.agents.domain.task

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.status.TaskStatus

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
        /**
         * Tool id this step nominates for execution. Populated by the planning
         * pipeline from the LLM's `toolToUse` field; `null` denotes a pure
         * reasoning step that performs no tool invocation.
         *
         * The executor routes strictly on this id with no keyword fallback.
         */
        val toolId: String? = null,
    ) : Task

    companion object {

        val blank: Task = Blank
    }
}
