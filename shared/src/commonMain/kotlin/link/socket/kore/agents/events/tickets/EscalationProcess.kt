package link.socket.kore.agents.events.tickets

/**
 * Defines the mechanism by which an escalation is resolved.
 * Each process type specifies the participants and format needed
 * to address the escalation.
 */
sealed class EscalationProcess {

    /**
     * Whether this process requires scheduling a meeting.
     */
    abstract val requiresMeeting: Boolean

    /**
     * Whether this process requires human involvement.
     */
    abstract val requiresHuman: Boolean

    /**
     * Escalation resolved through a meeting between agents.
     * Used for technical discussions, code reviews, and design decisions
     * that can be resolved by AI agents collaborating.
     */
    data object AgentMeeting : EscalationProcess() {
        override val requiresMeeting: Boolean = true
        override val requiresHuman: Boolean = false
    }

    /**
     * Escalation resolved through human approval without a formal meeting.
     * Used for straightforward approvals, sign-offs, and permissions
     * that don't require discussion.
     */
    data object HumanApproval : EscalationProcess() {
        override val requiresMeeting: Boolean = false
        override val requiresHuman: Boolean = true
    }

    /**
     * Escalation resolved through a meeting that includes human participants.
     * Used for strategic decisions, budget approvals, and scope changes
     * that require both discussion and human authority.
     */
    data object HumanMeeting : EscalationProcess() {
        override val requiresMeeting: Boolean = true
        override val requiresHuman: Boolean = true
    }

    /**
     * Escalation resolved by waiting for an external dependency.
     * Used when blocked by third-party APIs, vendor responses, or
     * other external factors outside the team's control.
     */
    data object ExternalDependency : EscalationProcess() {
        override val requiresMeeting: Boolean = false
        override val requiresHuman: Boolean = false
    }
}
