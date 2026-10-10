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
         * pipeline from the LLM's `toolToUse` field; `null` denotes a
         * reasoning step — one whose work is thinking rather than acting.
         *
         * The executor routes strictly on this id with no keyword fallback. A
         * null id is not a no-op: the executing agent carries the step out as
         * one model call and its text becomes the step's result (AMPR-407).
         */
        val toolId: String? = null,
    ) : Task

    companion object {

        val blank: Task = Blank
    }
}
