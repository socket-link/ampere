package link.socket.ampere.agents.events.messages.escalation

import link.socket.ampere.agents.events.tickets.Escalation
import link.socket.ampere.agents.events.tickets.PMPerceptionState
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority

/**
 * Default implementation of EscalationPolicy that classifies blockers
 * into Escalation types and evaluates project state for urgency.
 *
 * Classification is based on keyword matching in the blocker reason.
 * Urgency is elevated based on project-wide conditions like:
 * - Number of blocked tickets across the project
 * - Number of overdue tickets
 * - Priority of blocked items
 * - Agent workload and capacity constraints
 */
class DefaultEscalationPolicy(
    private val config: Config = Config(),
) : EscalationPolicy {

    /**
     * Configuration for escalation thresholds.
     */
    data class Config(
        /** Number of blocked tickets that triggers elevated urgency. */
        val blockedTicketThresholdElevated: Int = 3,
        /** Number of blocked tickets that triggers critical urgency. */
        val blockedTicketThresholdCritical: Int = 5,
        /** Number of overdue tickets that triggers elevated urgency. */
        val overdueTicketThresholdElevated: Int = 2,
        /** Number of overdue tickets that triggers critical urgency. */
        val overdueTicketThresholdCritical: Int = 5,
        /** Agent active ticket count that indicates high workload. */
        val agentHighWorkloadThreshold: Int = 5,
        /** Number of agents with high workload that triggers escalation. */
        val overloadedAgentThreshold: Int = 2,
    )

    override fun evaluate(context: EscalationContext): EscalationDecision {
        val reasons = mutableListOf<String>()
        var urgencyLevel = EscalationDecision.UrgencyLevel.NORMAL

        // Classify the blocker into an Escalation type
        val escalationType = classifyBlocker(context.blockingReason)
        reasons.add("Classified as ${escalationType::class.simpleName}: ${escalationType.description}")

        // Evaluate urgency based on ticket priority
        val priorityUrgency = evaluateTicketPriority(context.ticket)
        if (priorityUrgency.urgencyLevel > urgencyLevel) {
            urgencyLevel = priorityUrgency.urgencyLevel
            reasons.addAll(priorityUrgency.reasons)
        }

        // Evaluate urgency based on project state if available
        context.projectState?.let { projectState ->
            val projectUrgency = evaluateProjectState(projectState, context.ticket)
            if (projectUrgency.urgencyLevel > urgencyLevel) {
                urgencyLevel = projectUrgency.urgencyLevel
            }
            reasons.addAll(projectUrgency.reasons)
        }

        return EscalationDecision(
            escalationType = escalationType,
            urgencyLevel = urgencyLevel,
            reasons = reasons,
        )
    }

    // ==================== Blocker Classification ====================

    /**
     * Classify the blocker reason into an Escalation type using keyword matching.
     * This could be extended to use LLM classification for more accurate results.
     */
    private fun classifyBlocker(reason: String): Escalation {
        val lowerReason = reason.lowercase()

        // Check for specific escalation types based on keywords
        return when {
            // Authorization/Approval
            containsAny(lowerReason, "approval", "permission", "authorize", "sign-off", "signoff") ->
                Escalation.Decision.Authorization

            // Customer/External
            containsAny(lowerReason, "customer", "client", "user feedback", "stakeholder input") ->
                Escalation.External.Customer

            // Vendor/External dependency
            containsAny(lowerReason, "vendor", "third-party", "api availability", "external service") ->
                Escalation.External.Vendor

            // Budget/Cost
            containsAny(lowerReason, "budget", "cost", "expense", "purchase") ->
                Escalation.Budget.CostApproval

            // Resource allocation
            containsAny(lowerReason, "resource", "capacity", "team", "staffing") ->
                Escalation.Budget.ResourceAllocation

            // Timeline
            containsAny(lowerReason, "timeline", "deadline", "schedule", "delivery date") ->
                Escalation.Budget.Timeline

            // Scope expansion
            containsAny(lowerReason, "scope creep", "additional feature", "new requirement", "expanded") ->
                Escalation.Scope.Expansion

            // Scope reduction
            containsAny(lowerReason, "cut feature", "reduce scope", "simplify", "remove functionality") ->
                Escalation.Scope.Reduction

            // Priority conflict
            containsAny(lowerReason, "priority conflict", "competing", "urgent request") ->
                Escalation.Priorities.Conflict

            // Reprioritization
            containsAny(lowerReason, "reprioritize", "change priority", "urgent") ->
                Escalation.Priorities.Reprioritization

            // Cross-team dependency
            containsAny(lowerReason, "dependency", "blocked by", "waiting for team", "cross-team") ->
                Escalation.Priorities.Dependency

            // Product decision
            containsAny(lowerReason, "product decision", "feature direction", "business logic", "ux decision") ->
                Escalation.Decision.Product

            // Technical decision
            containsAny(lowerReason, "technical decision", "technology choice", "library", "implementation strategy") ->
                Escalation.Decision.Technical

            // Requirements clarification
            containsAny(lowerReason, "requirement", "clarification", "unclear", "ambiguous", "specification") ->
                Escalation.Discussion.Requirements

            // Architecture
            containsAny(lowerReason, "architecture", "system structure", "component", "pattern") ->
                Escalation.Discussion.Architecture

            // Design
            containsAny(lowerReason, "design", "ui", "ux", "interface", "layout") ->
                Escalation.Discussion.Design

            // Code review (default for technical blockers)
            containsAny(lowerReason, "review", "code", "pr", "pull request", "implementation") ->
                Escalation.Discussion.CodeReview

            // Default to requirements clarification for unclear blockers
            else -> Escalation.Discussion.Requirements
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it) }

    // ==================== Ticket Priority Evaluation ====================

    private data class UrgencyEvaluation(
        val urgencyLevel: EscalationDecision.UrgencyLevel,
        val reasons: List<String>,
    )

    private fun evaluateTicketPriority(ticket: Ticket): UrgencyEvaluation {
        return when (ticket.priority) {
            TicketPriority.CRITICAL -> UrgencyEvaluation(
                urgencyLevel = EscalationDecision.UrgencyLevel.CRITICAL,
                reasons = listOf("Critical priority ticket blocked"),
            )
            TicketPriority.HIGH -> UrgencyEvaluation(
                urgencyLevel = EscalationDecision.UrgencyLevel.ELEVATED,
                reasons = listOf("High priority ticket blocked"),
            )
            else -> UrgencyEvaluation(
                urgencyLevel = EscalationDecision.UrgencyLevel.NORMAL,
                reasons = emptyList(),
            )
        }
    }

    // ==================== Project State Evaluation ====================

    private fun evaluateProjectState(
        state: PMPerceptionState,
        currentTicket: Ticket,
    ): UrgencyEvaluation {
        val reasons = mutableListOf<String>()
        var urgencyLevel = EscalationDecision.UrgencyLevel.NORMAL

        // Check blocked ticket count (including this new blocker)
        val totalBlocked = state.blockedTickets.size + 1
        when {
            totalBlocked >= config.blockedTicketThresholdCritical -> {
                urgencyLevel = EscalationDecision.UrgencyLevel.CRITICAL
                reasons.add("Critical: $totalBlocked tickets now blocked (threshold: ${config.blockedTicketThresholdCritical})")
            }
            totalBlocked >= config.blockedTicketThresholdElevated -> {
                if (urgencyLevel < EscalationDecision.UrgencyLevel.ELEVATED) {
                    urgencyLevel = EscalationDecision.UrgencyLevel.ELEVATED
                }
                reasons.add("Elevated: $totalBlocked tickets now blocked (threshold: ${config.blockedTicketThresholdElevated})")
            }
        }

        // Check overdue ticket count
        val overdueCount = state.overdueTickets.size
        when {
            overdueCount >= config.overdueTicketThresholdCritical -> {
                if (urgencyLevel < EscalationDecision.UrgencyLevel.CRITICAL) {
                    urgencyLevel = EscalationDecision.UrgencyLevel.CRITICAL
                }
                reasons.add("Critical: $overdueCount tickets overdue (threshold: ${config.overdueTicketThresholdCritical})")
            }
            overdueCount >= config.overdueTicketThresholdElevated -> {
                if (urgencyLevel < EscalationDecision.UrgencyLevel.ELEVATED) {
                    urgencyLevel = EscalationDecision.UrgencyLevel.ELEVATED
                }
                reasons.add("Elevated: $overdueCount tickets overdue (threshold: ${config.overdueTicketThresholdElevated})")
            }
        }

        // Check if there are multiple critical/high priority blocked tickets
        val highPriorityBlocked = state.blockedTickets.count {
            it.priority == TicketPriority.CRITICAL || it.priority == TicketPriority.HIGH
        }
        if (highPriorityBlocked >= 2) {
            if (urgencyLevel < EscalationDecision.UrgencyLevel.ELEVATED) {
                urgencyLevel = EscalationDecision.UrgencyLevel.ELEVATED
            }
            reasons.add("Multiple high-priority tickets blocked ($highPriorityBlocked)")
        }

        // Check agent workload capacity
        val overloadedAgents = state.agentWorkloads.values.count {
            it.activeCount > config.agentHighWorkloadThreshold
        }
        if (overloadedAgents >= config.overloadedAgentThreshold) {
            if (urgencyLevel < EscalationDecision.UrgencyLevel.ELEVATED) {
                urgencyLevel = EscalationDecision.UrgencyLevel.ELEVATED
            }
            reasons.add("$overloadedAgents agents have high workload (>${config.agentHighWorkloadThreshold} active tickets)")
        }

        // Check if the assigned agent is already overloaded
        currentTicket.assignedAgentId?.let { agentId ->
            state.agentWorkloads[agentId]?.let { workload ->
                if (workload.blockedCount >= 2) {
                    if (urgencyLevel < EscalationDecision.UrgencyLevel.ELEVATED) {
                        urgencyLevel = EscalationDecision.UrgencyLevel.ELEVATED
                    }
                    reasons.add("Assigned agent $agentId has ${workload.blockedCount} blocked tickets")
                }
            }
        }

        return UrgencyEvaluation(
            urgencyLevel = urgencyLevel,
            reasons = reasons,
        )
    }
}
