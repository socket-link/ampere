package link.socket.ampere.agents.domain.task

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.events.tickets.TicketId

@Serializable
sealed interface TicketTask : Task {

    @Serializable
    data class CompleteSubticket(
        override val id: TicketId,
        override val status: TaskStatus,
    ) : TicketTask
}
