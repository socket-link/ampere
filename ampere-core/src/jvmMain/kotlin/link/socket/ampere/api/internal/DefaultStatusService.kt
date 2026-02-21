package link.socket.ampere.api.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import link.socket.ampere.agents.events.messages.ThreadViewService
import link.socket.ampere.agents.events.tickets.TicketViewService
import link.socket.ampere.api.model.AgentSnapshot
import link.socket.ampere.api.model.HealthLevel
import link.socket.ampere.api.model.HealthStatus
import link.socket.ampere.api.model.SystemSnapshot
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.api.service.StatusService

internal class DefaultStatusService(
    private val threadViewService: ThreadViewService,
    private val ticketViewService: TicketViewService,
    private val agentService: AgentService,
    private val workspace: String?,
) : StatusService {

    override suspend fun snapshot(): Result<SystemSnapshot> {
        return try {
            val threads = threadViewService.listActiveThreads().getOrElse { emptyList() }
            val tickets = ticketViewService.listActiveTickets().getOrElse { emptyList() }
            val agents = agentService.listAll()

            val totalMessages = threads.sumOf { it.messageCount }
            val escalatedThreads = threads.count { it.hasUnreadEscalations }

            Result.success(
                SystemSnapshot(
                    agents = agents,
                    activeTickets = tickets.size,
                    totalTickets = tickets.size,
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

    override fun health(): Flow<HealthStatus> = flow {
        while (true) {
            val agents = agentService.listAll()
            val tickets = ticketViewService.listActiveTickets().getOrElse { emptyList() }

            val activeAgents = agents.count { it.state == link.socket.ampere.api.model.AgentState.Active }
            val idleAgents = agents.count { it.state == link.socket.ampere.api.model.AgentState.Idle }
            val issues = mutableListOf<String>()

            val level = when {
                agents.isEmpty() -> {
                    issues.add("No agents configured")
                    HealthLevel.Unhealthy
                }
                activeAgents == 0 && tickets.isNotEmpty() -> {
                    issues.add("Pending tickets but no active agents")
                    HealthLevel.Degraded
                }
                else -> HealthLevel.Healthy
            }

            emit(
                HealthStatus(
                    overall = level,
                    activeAgents = activeAgents,
                    idleAgents = idleAgents,
                    pendingTickets = tickets.size,
                    issues = issues,
                )
            )

            delay(5000) // Poll every 5 seconds
        }
    }
}
