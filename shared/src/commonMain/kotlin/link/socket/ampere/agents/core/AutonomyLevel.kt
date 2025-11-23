package link.socket.ampere.agents.core

/**
 * Defines the level of autonomy an agent has when using a tool
 */
enum class AutonomyLevel {
    /**
     * Agent proposes action, waits for human approval
     */
    ASK_BEFORE_ACTION,

    /**
     * Agent acts immediately, notifies human of action taken
     */
    ACT_WITH_NOTIFICATION,

    /**
     * Agent acts independently, logs for later audit
     */
    FULLY_AUTONOMOUS,

    /**
     * Agent acts, self-reviews, and iterates without human involvement
     */
    SELF_CORRECTING,
}
