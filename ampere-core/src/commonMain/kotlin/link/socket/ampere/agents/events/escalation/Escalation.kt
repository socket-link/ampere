package link.socket.ampere.agents.events.escalation

import link.socket.ampere.agents.domain.AgentDescribable
import link.socket.ampere.agents.domain.AgentTypeDescriber

/**
 * Represents the type of escalation needed for a blocker.
 * Each escalation type defines what kind of issue it is and how it should be resolved.
 *
 * This hierarchy is designed to be used with LLM classification - an LLM analyzes
 * a blocker scenario and categorizes it into the appropriate escalation type.
 */
sealed class Escalation : AgentDescribable {

    /**
     * The process mechanism used to resolve this type of escalation.
     */
    abstract val escalationProcess: EscalationProcess

    /**
     * Human-readable description of this escalation type for LLM context.
     */
    abstract override val description: String

    override fun describeProperties(): Map<String, String> = mapOf(
        "escalationProcess" to escalationProcess.typeName,
    )

    // ==================== Discussion Escalations ====================

    /**
     * Escalations that require collaborative discussion to resolve.
     * These typically involve technical topics where multiple perspectives
     * help reach a better solution.
     */
    sealed class Discussion : Escalation() {

        /**
         * Code needs review before proceeding.
         * Includes pull requests, implementation approaches, and code quality concerns.
         */
        data object CodeReview : Discussion() {
            override val escalationProcess = EscalationProcess.AgentMeeting
            override val description = "Code needs review - includes PRs, implementation approaches, and quality concerns"
        }

        /**
         * Design decisions need discussion.
         * Includes UI/UX design, API design, and system design choices.
         */
        data object Design : Discussion() {
            override val escalationProcess = EscalationProcess.AgentMeeting
            override val description = "Design decisions needed - UI/UX, API design, or system design choices"
        }

        /**
         * Architecture questions need clarification.
         * Includes system structure, component relationships, and technical patterns.
         */
        data object Architecture : Discussion() {
            override val escalationProcess = EscalationProcess.AgentMeeting
            override val description = "Architecture clarification needed - system structure, components, or patterns"
        }

        /**
         * Requirements need clarification or refinement.
         * Includes unclear specifications, missing details, or conflicting requirements.
         */
        data object Requirements : Discussion() {
            override val escalationProcess = EscalationProcess.HumanMeeting
            override val description = "Requirements clarification needed - unclear specs, missing details, or conflicts"
        }
    }

    // ==================== Decision Escalations ====================

    /**
     * Escalations that require a decision to be made.
     * These block progress until someone with authority makes a choice.
     */
    sealed class Decision : Escalation() {

        /**
         * Technical decision needed between alternatives.
         * Includes technology choices, library selection, and implementation strategies.
         */
        data object Technical : Decision() {
            override val escalationProcess = EscalationProcess.AgentMeeting
            override val description = "Technical decision needed - technology choices, libraries, or implementation strategies"
        }

        /**
         * Product decision needed from stakeholders.
         * Includes feature direction, user experience choices, and business logic decisions.
         */
        data object Product : Decision() {
            override val escalationProcess = EscalationProcess.HumanMeeting
            override val description = "Product decision needed - feature direction, UX choices, or business logic"
        }

        /**
         * Authorization or approval needed to proceed.
         * Includes security approvals, compliance sign-offs, and access grants.
         */
        data object Authorization : Decision() {
            override val escalationProcess = EscalationProcess.HumanApproval
            override val description = "Authorization needed - security approvals, compliance, or access grants"
        }
    }

    // ==================== Budget Escalations ====================

    /**
     * Escalations related to budget, resources, or costs.
     * These typically require human approval due to financial implications.
     */
    sealed class Budget : Escalation() {

        /**
         * Resource allocation decision needed.
         * Includes team capacity, infrastructure resources, and tool licenses.
         */
        data object ResourceAllocation : Budget() {
            override val escalationProcess = EscalationProcess.HumanMeeting
            override val description = "Resource allocation needed - team capacity, infrastructure, or licenses"
        }

        /**
         * Cost approval needed for expenditure.
         * Includes service costs, tool purchases, and infrastructure expenses.
         */
        data object CostApproval : Budget() {
            override val escalationProcess = EscalationProcess.HumanApproval
            override val description = "Cost approval needed - service costs, purchases, or infrastructure expenses"
        }

        /**
         * Timeline or deadline negotiation needed.
         * Includes scope-timeline tradeoffs and delivery date discussions.
         */
        data object Timeline : Budget() {
            override val escalationProcess = EscalationProcess.HumanMeeting
            override val description = "Timeline negotiation needed - scope-timeline tradeoffs or delivery dates"
        }
    }

    // ==================== Priorities Escalations ====================

    /**
     * Escalations related to prioritization and scheduling.
     * These involve deciding what to work on and in what order.
     */
    sealed class Priorities : Escalation() {

        /**
         * Conflicting priorities need resolution.
         * Includes competing deadlines, resource conflicts, and dependency ordering.
         */
        data object Conflict : Priorities() {
            override val escalationProcess = EscalationProcess.HumanMeeting
            override val description = "Priority conflict - competing deadlines, resource conflicts, or dependency ordering"
        }

        /**
         * Reprioritization request for existing work.
         * Includes urgent requests, changed business needs, and blocking issues.
         */
        data object Reprioritization : Priorities() {
            override val escalationProcess = EscalationProcess.HumanApproval
            override val description = "Reprioritization request - urgent requests, changed needs, or blocking issues"
        }

        /**
         * Dependency on another team or project.
         * Includes cross-team coordination and external project dependencies.
         */
        data object Dependency : Priorities() {
            override val escalationProcess = EscalationProcess.AgentMeeting
            override val description = "Cross-team dependency - coordination with other teams or projects"
        }
    }

    // ==================== Scope Escalations ====================

    /**
     * Escalations related to project scope and boundaries.
     * These affect what is and isn't included in the work.
     */
    sealed class Scope : Escalation() {

        /**
         * Scope expansion or feature creep detected.
         * Includes additional requirements, expanded functionality, and new use cases.
         */
        data object Expansion : Scope() {
            override val escalationProcess = EscalationProcess.HumanMeeting
            override val description = "Scope expansion detected - additional requirements or new use cases"
        }

        /**
         * Scope reduction or feature cut needed.
         * Includes timeline pressure, technical limitations, and resource constraints.
         */
        data object Reduction : Scope() {
            override val escalationProcess = EscalationProcess.HumanMeeting
            override val description = "Scope reduction needed - timeline pressure or resource constraints"
        }

        /**
         * Scope boundaries unclear or undefined.
         * Includes ambiguous requirements and undefined edge cases.
         */
        data object Clarification : Scope() {
            override val escalationProcess = EscalationProcess.HumanMeeting
            override val description = "Scope clarification needed - ambiguous requirements or undefined boundaries"
        }
    }

    // ==================== External Escalations ====================

    /**
     * Escalations caused by external factors outside the team's control.
     */
    sealed class External : Escalation() {

        /**
         * Waiting for third-party vendor or service.
         * Includes API availability, support responses, and service provisioning.
         */
        data object Vendor : External() {
            override val escalationProcess = EscalationProcess.ExternalDependency
            override val description = "Waiting for vendor - API availability, support, or service provisioning"
        }

        /**
         * Waiting for customer or end-user input.
         * Includes user testing feedback, customer requirements, and stakeholder input.
         */
        data object Customer : External() {
            override val escalationProcess = EscalationProcess.HumanApproval
            override val description = "Waiting for customer - user feedback, requirements, or stakeholder input"
        }
    }

    companion object {
        /**
         * Returns all possible escalation types for LLM classification context.
         * The LLM uses these descriptions to categorize blocker scenarios.
         */
        fun allTypes(): List<Escalation> = listOf(
            // Discussion
            Discussion.CodeReview,
            Discussion.Design,
            Discussion.Architecture,
            Discussion.Requirements,
            // Decision
            Decision.Technical,
            Decision.Product,
            Decision.Authorization,
            // Budget
            Budget.ResourceAllocation,
            Budget.CostApproval,
            Budget.Timeline,
            // Priorities
            Priorities.Conflict,
            Priorities.Reprioritization,
            Priorities.Dependency,
            // Scope
            Scope.Expansion,
            Scope.Reduction,
            Scope.Clarification,
            // External
            External.Vendor,
            External.Customer,
        )

        /**
         * Returns a formatted string of all escalation types and their descriptions
         * suitable for use in LLM prompts.
         */
        fun allTypesForPrompt(): String = AgentTypeDescriber.formatGroupedByHierarchy(
            types = allTypes(),
            title = "Available escalation types:",
        )
    }
}
