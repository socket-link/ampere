package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Instant

/**
 * Represents the current activity state of an agent for dashboard display.
 */
data class AgentActivityState(
    val agentId: String,
    val displayName: String,
    val currentState: AgentState,
    val lastActivityTimestamp: Instant,
    val consecutiveCognitiveCycles: Int,
    val isIdle: Boolean
)

/**
 * High-level states an agent can be in.
 */
enum class AgentState(val displayText: String) {
    THINKING("thinking"),
    WORKING("working"),
    IDLE("idle"),
    IN_MEETING("in meeting"),
    WAITING("waiting")
}
