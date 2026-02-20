package link.socket.ampere.api.internal

import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.agents.events.tickets.TicketViewService
import link.socket.ampere.api.model.SystemSnapshot
import link.socket.ampere.api.service.StatusService

internal class DefaultStatusService(
    private val threadViewService: ThreadViewService,
    private val ticketViewService: TicketViewService,
    private val workspace: String?,
) : StatusService {

    override suspend fun snapshot(): Result<SystemSnapshot> {
        return try {
            val threads = threadViewService.listActiveThreads().getOrElse { emptyList() }
            val tickets = ticketViewService.listActiveTickets().getOrElse { emptyList() }

            val totalMessages = threads.sumOf { it.messageCount }
            val escalatedThreads = threads.count { it.hasUnreadEscalations }

            Result.success(
                SystemSnapshot(
                    activeTickets = tickets.size,
                    totalTickets = tickets.size, // Active tickets is what we have access to here
                    activeThreads = threads.size,
                    totalMessages = totalMessages,
                    escalatedThreads = escalatedThreads,
                    workspace = workspace,
                ),
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
