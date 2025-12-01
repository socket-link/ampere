package link.socket.ampere.agents.events.escalation

import link.socket.ampere.agents.core.AgentDescribable

/**
 * Defines the mechanism by which an escalation is resolved.
 * Each process type specifies the participants and format needed
 * to address the escalation.
 */
sealed class EscalationProcess : AgentDescribable {

    /**
     * Whether this process requires scheduling a meeting.
     */
    abstract val requiresMeeting: Boolean

    /**
     * Whether this process requires human involvement.
     */
    abstract val requiresHuman: Boolean

    override fun describeProperties(): Map<String, String> = mapOf(
        "requiresMeeting" to requiresMeeting.toString(),
        "requiresHuman" to requiresHuman.toString()
    )

    /**
     * Escalation resolved through a meeting between agents.
     * Used for technical discussions, code reviews, and design decisions
     * that can be resolved by AI agents collaborating.
     */
    data object AgentMeeting : EscalationProcess() {
        override val requiresMeeting: Boolean = true
        override val requiresHuman: Boolean = false
        override val description: String = "Resolved through agent meeting - technical discussions and decisions handled by AI agents"
    }

    /**
     * Escalation resolved through human approval without a formal meeting.
     * Used for straightforward approvals, sign-offs, and permissions
     * that don't require discussion.
     */
    data object HumanApproval : EscalationProcess() {
        override val requiresMeeting: Boolean = false
        override val requiresHuman: Boolean = true
        override val description: String = "Resolved through human approval - straightforward sign-offs and permissions without discussion"
    }

    /**
     * Escalation resolved through a meeting that includes human participants.
     * Used for strategic decisions, budget approvals, and scope changes
     * that require both discussion and human authority.
     */
    data object HumanMeeting : EscalationProcess() {
        override val requiresMeeting: Boolean = true
        override val requiresHuman: Boolean = true
        override val description: String = "Resolved through human meeting - strategic decisions requiring both discussion and human authority"
    }

    /**
     * Escalation resolved by waiting for an external dependency.
     * Used when blocked by third-party APIs, vendor responses, or
     * other external factors outside the team's control.
     */
    data object ExternalDependency : EscalationProcess() {
        override val requiresMeeting: Boolean = false
        override val requiresHuman: Boolean = false
        override val description: String = "Waiting for external dependency - third-party APIs, vendor responses, or other external factors"
    }
}
