package link.socket.kore.agents.events.tickets

/**
 * Represents the type of escalation needed for a blocker.
 * Each escalation type defines what kind of issue it is and how it should be resolved.
 *
 * This hierarchy is designed to be used with LLM classification - an LLM analyzes
 * a blocker scenario and categorizes it into the appropriate escalation type.
 */
sealed class Escalation {

    /**
     * The process mechanism used to resolve this type of escalation.
     */
    abstract val process: EscalationProcess

    /**
     * Human-readable description of this escalation type for LLM context.
     */
    abstract val description: String

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
            override val process = EscalationProcess.AgentMeeting
            override val description = "Code needs review - includes PRs, implementation approaches, and quality concerns"
        }

        /**
         * Design decisions need discussion.
         * Includes UI/UX design, API design, and system design choices.
         */
        data object Design : Discussion() {
            override val process = EscalationProcess.AgentMeeting
            override val description = "Design decisions needed - UI/UX, API design, or system design choices"
        }

        /**
         * Architecture questions need clarification.
         * Includes system structure, component relationships, and technical patterns.
         */
        data object Architecture : Discussion() {
            override val process = EscalationProcess.AgentMeeting
            override val description = "Architecture clarification needed - system structure, components, or patterns"
        }

        /**
         * Requirements need clarification or refinement.
         * Includes unclear specifications, missing details, or conflicting requirements.
         */
        data object Requirements : Discussion() {
            override val process = EscalationProcess.HumanMeeting
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
            override val process = EscalationProcess.AgentMeeting
            override val description = "Technical decision needed - technology choices, libraries, or implementation strategies"
        }

        /**
         * Product decision needed from stakeholders.
         * Includes feature direction, user experience choices, and business logic decisions.
         */
        data object Product : Decision() {
            override val process = EscalationProcess.HumanMeeting
            override val description = "Product decision needed - feature direction, UX choices, or business logic"
        }

        /**
         * Authorization or approval needed to proceed.
         * Includes security approvals, compliance sign-offs, and access grants.
         */
        data object Authorization : Decision() {
            override val process = EscalationProcess.HumanApproval
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
            override val process = EscalationProcess.HumanMeeting
            override val description = "Resource allocation needed - team capacity, infrastructure, or licenses"
        }

        /**
         * Cost approval needed for expenditure.
         * Includes service costs, tool purchases, and infrastructure expenses.
         */
        data object CostApproval : Budget() {
            override val process = EscalationProcess.HumanApproval
            override val description = "Cost approval needed - service costs, purchases, or infrastructure expenses"
        }

        /**
         * Timeline or deadline negotiation needed.
         * Includes scope-timeline tradeoffs and delivery date discussions.
         */
        data object Timeline : Budget() {
            override val process = EscalationProcess.HumanMeeting
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
            override val process = EscalationProcess.HumanMeeting
            override val description = "Priority conflict - competing deadlines, resource conflicts, or dependency ordering"
        }

        /**
         * Reprioritization request for existing work.
         * Includes urgent requests, changed business needs, and blocking issues.
         */
        data object Reprioritization : Priorities() {
            override val process = EscalationProcess.HumanApproval
            override val description = "Reprioritization request - urgent requests, changed needs, or blocking issues"
        }

        /**
         * Dependency on another team or project.
         * Includes cross-team coordination and external project dependencies.
         */
        data object Dependency : Priorities() {
            override val process = EscalationProcess.AgentMeeting
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
            override val process = EscalationProcess.HumanMeeting
            override val description = "Scope expansion detected - additional requirements or new use cases"
        }

        /**
         * Scope reduction or feature cut needed.
         * Includes timeline pressure, technical limitations, and resource constraints.
         */
        data object Reduction : Scope() {
            override val process = EscalationProcess.HumanMeeting
            override val description = "Scope reduction needed - timeline pressure or resource constraints"
        }

        /**
         * Scope boundaries unclear or undefined.
         * Includes ambiguous requirements and undefined edge cases.
         */
        data object Clarification : Scope() {
            override val process = EscalationProcess.HumanMeeting
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
            override val process = EscalationProcess.ExternalDependency
            override val description = "Waiting for vendor - API availability, support, or service provisioning"
        }

        /**
         * Waiting for customer or end-user input.
         * Includes user testing feedback, customer requirements, and stakeholder input.
         */
        data object Customer : External() {
            override val process = EscalationProcess.HumanApproval
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
        fun allTypesForPrompt(): String = buildString {
            appendLine("Available escalation types:")
            appendLine()

            appendLine("## Discussion")
            appendLine("- CodeReview: ${Discussion.CodeReview.description}")
            appendLine("- Design: ${Discussion.Design.description}")
            appendLine("- Architecture: ${Discussion.Architecture.description}")
            appendLine("- Requirements: ${Discussion.Requirements.description}")
            appendLine()

            appendLine("## Decision")
            appendLine("- Technical: ${Decision.Technical.description}")
            appendLine("- Product: ${Decision.Product.description}")
            appendLine("- Authorization: ${Decision.Authorization.description}")
            appendLine()

            appendLine("## Budget")
            appendLine("- ResourceAllocation: ${Budget.ResourceAllocation.description}")
            appendLine("- CostApproval: ${Budget.CostApproval.description}")
            appendLine("- Timeline: ${Budget.Timeline.description}")
            appendLine()

            appendLine("## Priorities")
            appendLine("- Conflict: ${Priorities.Conflict.description}")
            appendLine("- Reprioritization: ${Priorities.Reprioritization.description}")
            appendLine("- Dependency: ${Priorities.Dependency.description}")
            appendLine()

            appendLine("## Scope")
            appendLine("- Expansion: ${Scope.Expansion.description}")
            appendLine("- Reduction: ${Scope.Reduction.description}")
            appendLine("- Clarification: ${Scope.Clarification.description}")
            appendLine()

            appendLine("## External")
            appendLine("- Vendor: ${External.Vendor.description}")
            appendLine("- Customer: ${External.Customer.description}")
        }

        /**
         * Parses an escalation type from a string identifier.
         * Used to convert LLM output back to typed escalation.
         *
         * @param identifier The escalation type identifier (e.g., "Discussion.CodeReview")
         * @return The matching Escalation type, or null if not found.
         */
        fun fromIdentifier(identifier: String): Escalation? {
            val normalized = identifier.trim()
            return when {
                // Discussion
                normalized.contains("CodeReview", ignoreCase = true) -> Discussion.CodeReview
                normalized.contains("Design", ignoreCase = true) &&
                    !normalized.contains("Redesign", ignoreCase = true) -> Discussion.Design
                normalized.contains("Architecture", ignoreCase = true) -> Discussion.Architecture
                normalized.contains("Requirements", ignoreCase = true) -> Discussion.Requirements

                // Decision
                normalized.contains("Technical", ignoreCase = true) -> Decision.Technical
                normalized.contains("Product", ignoreCase = true) -> Decision.Product
                normalized.contains("Authorization", ignoreCase = true) -> Decision.Authorization

                // Budget
                normalized.contains("ResourceAllocation", ignoreCase = true) -> Budget.ResourceAllocation
                normalized.contains("CostApproval", ignoreCase = true) -> Budget.CostApproval
                normalized.contains("Timeline", ignoreCase = true) -> Budget.Timeline

                // Priorities
                normalized.contains("Conflict", ignoreCase = true) -> Priorities.Conflict
                normalized.contains("Reprioritization", ignoreCase = true) -> Priorities.Reprioritization
                normalized.contains("Dependency", ignoreCase = true) -> Priorities.Dependency

                // Scope
                normalized.contains("Expansion", ignoreCase = true) -> Scope.Expansion
                normalized.contains("Reduction", ignoreCase = true) -> Scope.Reduction
                normalized.contains("Clarification", ignoreCase = true) -> Scope.Clarification

                // External
                normalized.contains("Vendor", ignoreCase = true) -> External.Vendor
                normalized.contains("Customer", ignoreCase = true) -> External.Customer

                else -> null
            }
        }
    }
}
