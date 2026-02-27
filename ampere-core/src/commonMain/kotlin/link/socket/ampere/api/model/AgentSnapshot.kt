package link.socket.ampere.api.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId

/**
 * Describes the possible states of an agent.
 */
@link.socket.ampere.api.AmpereStableApi
@Serializable
enum class AgentState {
    Active,
    Idle,
    Dormant,
    Paused,
}

/**
 * Point-in-time view of an agent's state.
 */
@link.socket.ampere.api.AmpereStableApi
@Serializable
data class AgentSnapshot(
    val id: AgentId,
    val role: String,
    val state: AgentState,
    val currentTask: String?,
    val sparkStack: List<String>,
    val lastActivity: Instant,
)
