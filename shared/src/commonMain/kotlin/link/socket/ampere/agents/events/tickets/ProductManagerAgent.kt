package link.socket.ampere.agents.events.tickets

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AgentId

/**
 * Perception state for Product Manager agent.
 *
 * Contains all the analytics data a PM agent needs to understand the current
 * state of the backlog and make informed decisions about prioritization and
 * assignment.
 */
@Serializable
data class PMPerceptionState(
    /** Summary of the overall backlog state. */
    val backlogSummary: BacklogSummary,
    /** Workloads for tracked agents. */
    val agentWorkloads: Map<AgentId, AgentWorkload>,
    /** Tickets with upcoming deadlines. */
    val upcomingDeadlines: List<Ticket>,
    /** List of currently blocked tickets requiring attention. */
    val blockedTickets: List<Ticket>,
    /** List of overdue tickets requiring immediate attention. */
    val overdueTickets: List<Ticket>,
) {
    /**
     * Formats the perception state as structured text suitable for LLM consumption.
     *
     * Highlights blocked tickets, overdue items, and capacity constraints to
     * enable the PM agent to make informed decisions.
     */
    fun toPerceptionText(): String = buildString {
        appendLine("=== PM Agent Perception State ===")
        appendLine("Generated at: ${Clock.System.now()}")
        appendLine()

        // Overall backlog summary
        append(backlogSummary.toPerceptionText())
        appendLine()

        // Highlight critical issues first
        if (blockedTickets.isNotEmpty()) {
            appendLine("=== BLOCKED TICKETS (Requires Attention) ===")
            blockedTickets.forEach { ticket ->
                appendLine("  - [${ticket.priority}] ${ticket.title}")
                appendLine("    ID: ${ticket.id}")
                appendLine("    Type: ${ticket.type}")
                if (ticket.assignedAgentId != null) {
                    appendLine("    Assigned to: ${ticket.assignedAgentId}")
                } else {
                    appendLine("    Assigned to: UNASSIGNED")
                }
            }
            appendLine()
        }

        if (overdueTickets.isNotEmpty()) {
            appendLine("=== OVERDUE TICKETS (Immediate Action Required) ===")
            overdueTickets.forEach { ticket ->
                appendLine("  - [${ticket.priority}] ${ticket.title}")
                appendLine("    ID: ${ticket.id}")
                appendLine("    Due: ${ticket.dueDate}")
                appendLine("    Status: ${ticket.status}")
                if (ticket.assignedAgentId != null) {
                    appendLine("    Assigned to: ${ticket.assignedAgentId}")
                } else {
                    appendLine("    Assigned to: UNASSIGNED")
                }
            }
            appendLine()
        }

        // Upcoming deadlines
        if (upcomingDeadlines.isNotEmpty()) {
            appendLine("=== Upcoming Deadlines ===")
            upcomingDeadlines.forEach { ticket ->
                appendLine("  - [${ticket.priority}] ${ticket.title}")
                appendLine("    Due: ${ticket.dueDate}")
                appendLine("    Status: ${ticket.status}")
            }
            appendLine()
        }

        // Agent workloads
        if (agentWorkloads.isNotEmpty()) {
            appendLine("=== Agent Workloads ===")
            agentWorkloads.values.forEach { workload ->
                appendLine("Agent: ${workload.agentId}")
                appendLine("  Total assigned: ${workload.assignedTickets.size}")
                appendLine("  In progress: ${workload.inProgressCount}")
                appendLine("  Blocked: ${workload.blockedCount}")
                appendLine("  Completed: ${workload.completedCount}")

                // Highlight capacity constraints
                if (workload.blockedCount > 0) {
                    appendLine("  ⚠ Has blocked tickets")
                }
                if (workload.activeCount > 5) {
                    appendLine("  ⚠ High workload (${workload.activeCount} active)")
                }
                appendLine()
            }
        }
    }

    companion object {
        /**
         * Returns an empty perception state.
         */
        fun empty(): PMPerceptionState = PMPerceptionState(
            backlogSummary = BacklogSummary.empty(),
            agentWorkloads = emptyMap(),
            upcomingDeadlines = emptyList(),
            blockedTickets = emptyList(),
            overdueTickets = emptyList(),
        )
    }
}

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
    ): PMPerceptionState {
        // Get backlog summary
        val backlogSummary = ticketOrchestrator.getBacklogSummary()
            .getOrElse { BacklogSummary.empty() }

        // Get agent workloads
        val agentWorkloads = agentIds.associateWith { agentId ->
            ticketOrchestrator.getAgentWorkload(agentId)
                .getOrElse { AgentWorkload.empty(agentId) }
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
                .filter { it.status == TicketStatus.BLOCKED }
                .distinctBy { it.id }
        } else {
            emptyList()
        }

        // Extract overdue tickets from upcoming deadlines query
        // We need a separate query for overdue - tickets with due date in the past
        val now = kotlinx.datetime.Clock.System.now()
        val overdueTickets = agentWorkloads.values
            .flatMap { it.assignedTickets }
            .filter { ticket ->
                ticket.dueDate != null &&
                    ticket.dueDate < now &&
                    ticket.status != TicketStatus.DONE
            }
            .distinctBy { it.id }

        return PMPerceptionState(
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
