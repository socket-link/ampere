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
    val isIdle: Boolean,
    /** The cognitive affinity name (e.g., "ANALYTICAL", "EXPLORATORY"). */
    val affinityName: String? = null,
    /** Names of Sparks currently on the stack, in order of application. */
    val sparkNames: List<String> = emptyList(),
    /** The current Spark stack depth. */
    val sparkDepth: Int = 0,
    /** Human-readable description of the current cognitive state. */
    val cognitiveStateDescription: String? = null
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
