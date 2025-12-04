package link.socket.ampere.agents.events.tickets

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.type.AgentId
import link.socket.ampere.agents.domain.concept.status.TicketStatus

/**
 * Summary of the current backlog state for PM agent perception.
 *
 * Provides aggregate statistics about tickets in the system, enabling
 * PM agents to understand the overall state of work and make informed
 * decisions about prioritization and assignment.
 */
@Serializable
data class BacklogSummary(
    /** Total number of tickets in the system. */
    val totalTickets: Int,
    /** Count of tickets grouped by status. */
    val ticketsByStatus: Map<TicketStatus, Int>,
    /** Count of tickets grouped by priority. */
    val ticketsByPriority: Map<TicketPriority, Int>,
    /** Count of tickets grouped by type. */
    val ticketsByType: Map<TicketType, Int>,
    /** Number of tickets currently blocked. */
    val blockedCount: Int,
    /** Number of tickets past their due date. */
    val overdueCount: Int,
) {
    /**
     * Formats the backlog summary as a structured text suitable for LLM consumption.
     */
    fun toPerceptionText(): String = buildString {
        appendLine("=== Backlog Summary ===")
        appendLine("Total Tickets: $totalTickets")
        appendLine()

        appendLine("By Status:")
        ticketsByStatus.entries
            .sortedBy { it.key.name }
            .forEach { (status, count) ->
                appendLine("  - $status: $count")
            }
        appendLine()

        appendLine("By Priority:")
        ticketsByPriority.entries
            .sortedByDescending { it.key.ordinal }
            .forEach { (priority, count) ->
                appendLine("  - $priority: $count")
            }
        appendLine()

        appendLine("By Type:")
        ticketsByType.entries
            .sortedBy { it.key.name }
            .forEach { (type, count) ->
                appendLine("  - $type: $count")
            }
        appendLine()

        appendLine("Attention Required:")
        appendLine("  - Blocked: $blockedCount")
        appendLine("  - Overdue: $overdueCount")
    }

    companion object {
        /**
         * Returns an empty backlog summary with all counts set to zero.
         */
        fun empty(): BacklogSummary = BacklogSummary(
            totalTickets = 0,
            ticketsByStatus = emptyMap(),
            ticketsByPriority = emptyMap(),
            ticketsByType = emptyMap(),
            blockedCount = 0,
            overdueCount = 0,
        )
    }
}

/**
 * Summary of an agent's current workload.
 *
 * Enables PM agents to assess capacity before making new assignments
 * and to identify agents who may be overloaded or blocked.
 */
@Serializable
data class AgentWorkload(
    /** ID of the agent this workload represents. */
    val agentId: AgentId,
    /** List of tickets currently assigned to this agent. */
    val assignedTickets: List<Ticket>,
    /** Number of tickets currently in progress. */
    val inProgressCount: Int,
    /** Number of tickets currently blocked. */
    val blockedCount: Int,
    /** Number of tickets completed by this agent. */
    val completedCount: Int,
) {
    /**
     * Total number of active tickets (not completed).
     */
    val activeCount: Int
        get() = assignedTickets.count { it.status != TicketStatus.Done }

    /**
     * Formats the agent workload as a structured text suitable for LLM consumption.
     */
    fun toPerceptionText(): String = buildString {
        appendLine("=== Agent Workload: $agentId ===")
        appendLine("Total Assigned: ${assignedTickets.size}")
        appendLine("Active: $activeCount")
        appendLine("  - In Progress: $inProgressCount")
        appendLine("  - Blocked: $blockedCount")
        appendLine("Completed: $completedCount")

        if (assignedTickets.isNotEmpty()) {
            appendLine()
            appendLine("Assigned Tickets:")
            assignedTickets.forEach { ticket ->
                appendLine("  - [${ticket.priority}] ${ticket.title} (${ticket.status})")
            }
        }
    }

    companion object {
        /**
         * Returns an empty workload for the specified agent.
         */
        fun empty(agentId: AgentId): AgentWorkload = AgentWorkload(
            agentId = agentId,
            assignedTickets = emptyList(),
            inProgressCount = 0,
            blockedCount = 0,
            completedCount = 0,
        )
    }
}
