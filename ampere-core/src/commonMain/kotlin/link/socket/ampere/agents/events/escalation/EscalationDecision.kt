package link.socket.ampere.agents.events.escalation

import link.socket.ampere.agents.events.escalation.Escalation

/**
 * Represents the decision made by an escalation policy.
 *
 * Contains the classified escalation type and urgency information
 * based on both the blocker reason and overall project state.
 */
data class EscalationDecision(
    /** The classified escalation type determining how to handle this blocker. */
    val escalationType: Escalation,
    /** The urgency level of the escalation based on project state. */
    val urgencyLevel: UrgencyLevel,
    /** Reasons explaining the escalation classification and urgency. */
    val reasons: List<String>,
) {
    /**
     * Urgency levels for escalation decisions based on project state.
     */
    enum class UrgencyLevel {
        /** Normal escalation - no immediate action required. */
        NORMAL,
        /** Elevated urgency - should be addressed soon. */
        ELEVATED,
        /** Critical urgency - requires immediate attention. */
        CRITICAL,
    }
}

/**
 * Compare urgency levels for ordering.
 */
internal operator fun EscalationDecision.UrgencyLevel.compareTo(
    other: EscalationDecision.UrgencyLevel,
): Int = this.ordinal.compareTo(other.ordinal)
