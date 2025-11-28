package link.socket.ampere.agents.implementations.pm

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.tickets.AgentWorkload
import link.socket.ampere.agents.events.tickets.BacklogSummary
import link.socket.ampere.agents.events.tickets.Ticket

/**
 * Perception state for Product Manager agent.
 *
 * Contains all the analytics data a PM agent needs to understand the current
 * state of the backlog and make informed decisions about prioritization and
 * assignment.
 */
@Serializable
data class ProductManagerState(
    /** Summary of the overall backlog state */
    val backlogSummary: BacklogSummary,
    /** Workloads for tracked agents */
    val agentWorkloads: Map<AgentId, AgentWorkload>,
    /** Tickets with upcoming deadlines. */
    val upcomingDeadlines: List<Ticket>,
    /** List of currently blocked tickets requiring attention */
    val blockedTickets: List<Ticket>,
    /** List of overdue tickets requiring immediate attention */
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

    companion object Companion {
        /**
         * Returns an empty perception state.
         */
        fun empty(): ProductManagerState = ProductManagerState(
            backlogSummary = BacklogSummary.empty(),
            agentWorkloads = emptyMap(),
            upcomingDeadlines = emptyList(),
            blockedTickets = emptyList(),
            overdueTickets = emptyList(),
        )
    }
}
