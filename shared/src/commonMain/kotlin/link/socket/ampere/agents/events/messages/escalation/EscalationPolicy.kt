package link.socket.ampere.agents.events.messages.escalation

/**
 * Policy interface for classifying blockers and determining escalation behavior.
 *
 * Implementations analyze blocker reasons and project state to:
 * 1. Classify the blocker into an appropriate Escalation type
 * 2. Determine urgency based on project-wide conditions
 */
interface EscalationPolicy {
    /**
     * Evaluate escalation for the given context.
     *
     * @param context The escalation context containing ticket, blocker, and project state.
     * @return The escalation decision with type classification and urgency.
     */
    fun evaluate(context: EscalationContext): EscalationDecision
}
