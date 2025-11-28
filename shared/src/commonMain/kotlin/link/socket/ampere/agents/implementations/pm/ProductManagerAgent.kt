package link.socket.ampere.agents.implementations.pm

import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.status.TicketStatus
import link.socket.ampere.agents.events.tickets.AgentWorkload
import link.socket.ampere.agents.events.tickets.BacklogSummary
import link.socket.ampere.agents.events.tickets.TicketOrchestrator

/**
 * Product Manager Agent that perceives and manages the backlog state.
 *
 * This agent is responsible for understanding the current state of work,
 * identifying blockers and overdue items, assessing agent capacity, and
 * making decisions about prioritization and assignment.
 */
class ProductManagerAgent(
    val id: AgentId,
    private val ticketOrchestrator: TicketOrchestrator,
) {
    /**
     * Perceive the current state of the backlog and agent workloads.
     *
     * Retrieves analytics from the ticket system and formats them into
     * a perception state suitable for LLM consumption.
     *
     * @param agentIds Optional list of agent IDs to get workload for. If empty, no workloads are retrieved.
     * @param deadlineDaysAhead Number of days to look ahead for upcoming deadlines (default: 7).
     * @return The PM perception state containing backlog analytics.
     */
    suspend fun perceive(
        agentIds: List<AgentId> = emptyList(),
        deadlineDaysAhead: Int = 7,
    ): ProductManagerState {
        // Get backlog summary
        val backlogSummary = ticketOrchestrator.getBacklogSummary()
            .getOrElse { BacklogSummary.Companion.empty() }

        // Get agent workloads
        val agentWorkloads = agentIds.associateWith { agentId ->
            ticketOrchestrator.getAgentWorkload(agentId)
                .getOrElse { AgentWorkload.Companion.empty(agentId) }
        }

        // Get upcoming deadlines
        val upcomingDeadlines = ticketOrchestrator.getUpcomingDeadlines(deadlineDaysAhead)
            .getOrElse { emptyList() }

        // Get blocked and overdue tickets from repository
        val allTicketsResult = ticketOrchestrator.getBacklogSummary()

        // Extract blocked tickets
        val blockedTickets = if (allTicketsResult.isSuccess) {
            // We need to get the actual blocked tickets, not just the count
            // For now, collect from agent workloads and deduplicate
            agentWorkloads.values
                .flatMap { it.assignedTickets }
                .filter { it.status == TicketStatus.Blocked }
                .distinctBy { it.id }
        } else {
            emptyList()
        }

        // Extract overdue tickets from upcoming deadlines query
        // We need a separate query for overdue - tickets with due date in the past
        val now = Clock.System.now()
        val overdueTickets = agentWorkloads.values
            .flatMap { it.assignedTickets }
            .filter { ticket ->
                ticket.dueDate != null &&
                    ticket.dueDate < now &&
                    ticket.status != TicketStatus.Done
            }
            .distinctBy { it.id }

        return ProductManagerState(
            backlogSummary = backlogSummary,
            agentWorkloads = agentWorkloads,
            upcomingDeadlines = upcomingDeadlines,
            blockedTickets = blockedTickets,
            overdueTickets = overdueTickets,
        )
    }

    /**
     * Get a formatted text representation of the current perception state.
     *
     * This is a convenience method that calls perceive() and formats the result
     * as structured text suitable for LLM consumption.
     *
     * @param agentIds Optional list of agent IDs to get workload for.
     * @param deadlineDaysAhead Number of days to look ahead for upcoming deadlines.
     * @return Formatted perception text.
     */
    suspend fun perceiveAsText(
        agentIds: List<AgentId> = emptyList(),
        deadlineDaysAhead: Int = 7,
    ): String {
        return perceive(agentIds, deadlineDaysAhead).toPerceptionText()
    }
}
