package link.socket.ampere.agents.definition.product

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.reasoning.perception
import link.socket.ampere.agents.domain.reasoning.perceptionText
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.Task
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
data class ProductAgentState(
    /** Current task outcome */
    val outcome: Outcome,
    /** Current task being worked on */
    val task: Task,
    /** Current plan for task execution */
    val plan: Plan,
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
) : AgentState() {

    /**
     * Converts the state into a structured perception for LLM consumption.
     *
     * Uses the perception builder pattern consistent with CodeWriterAgent.
     */
    fun toPerception(): Perception<ProductAgentState> = perception(this) {
        header("Product Manager Perception State")
        timestamp()

        setNewOutcome(outcome)
        setNewTask(task)
        setNewPlan(plan)
    }

    /**
     * Formats the perception state as structured text suitable for LLM consumption.
     *
     * Highlights blocked tickets, overdue items, and capacity constraints to
     * enable the PM agent to make informed decisions.
     */
    fun toPerceptionText(): String = perceptionText {
        header("PM Agent - State Perception")
        timestamp()

        // Overall backlog summary
        text(backlogSummary.toPerceptionText())
        blankLine()

        // Highlight critical issues first
        sectionIf(blockedTickets.isNotEmpty(), "BLOCKED TICKETS (Requires Attention)") {
            blockedTickets.forEach { ticket ->
                ticket(ticket)
            }
        }

        sectionIf(overdueTickets.isNotEmpty(), "OVERDUE TICKETS (Immediate Action Required)") {
            overdueTickets.forEach { ticket ->
                ticket(ticket)
            }
        }

        // Upcoming deadlines
        sectionIf(upcomingDeadlines.isNotEmpty(), "Upcoming Deadlines") {
            upcomingDeadlines.forEach { ticket ->
                ticket(ticket)
            }
        }

        // Agent workloads
        sectionIf(agentWorkloads.isNotEmpty(), "Agent Workloads") {
            agentWorkloads.values.forEach { workload ->
                line("Agent: ${workload.agentId}")
                field("Total assigned", workload.assignedTickets.size)
                field("In progress", workload.inProgressCount)
                field("Blocked", workload.blockedCount)
                field("Completed", workload.completedCount)

                // Highlight capacity constraints
                if (workload.blockedCount > 0) {
                    warning("Has blocked tickets")
                }
                if (workload.activeCount > 5) {
                    warning("High workload (${workload.activeCount} active)")
                }
                line("")
            }
        }
    }

    companion object Companion {
        /**
         * Returns an empty perception state.
         */
        val blank = ProductAgentState(
            outcome = Outcome.Companion.blank,
            task = Task.Blank,
            plan = Plan.Companion.blank,
            backlogSummary = BacklogSummary.Companion.empty(),
            agentWorkloads = emptyMap(),
            upcomingDeadlines = emptyList(),
            blockedTickets = emptyList(),
            overdueTickets = emptyList(),
        )
    }
}
