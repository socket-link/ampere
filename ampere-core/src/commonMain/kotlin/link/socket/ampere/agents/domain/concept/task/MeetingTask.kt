package link.socket.ampere.agents.domain.concept.task

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.status.TaskStatus

typealias AgendaItemId = TaskId

@Serializable
sealed interface MeetingTask : Task {

    /** A discrete topic or activity to discuss within a meeting agenda. */
    @Serializable
    data class AgendaItem(
        override val id: AgendaItemId,
        val title: String,
        override val status: TaskStatus = TaskStatus.Pending,
        val description: String? = null,
        val assignedTo: AssignedTo? = null,
    ) : MeetingTask
}
