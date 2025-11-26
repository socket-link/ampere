package link.socket.ampere.agents.core.tasks

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.events.tickets.TicketId

typealias SubticketId = TicketId

@Serializable
sealed interface TicketTask : Task {

    @Serializable
    data class CompleteSubticket(
        override val id: SubticketId,
        override val status: TaskStatus,
    ) : TicketTask
}
