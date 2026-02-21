package link.socket.ampere.api.model

import kotlinx.serialization.Serializable

/**
 * Point-in-time view of the entire AMPERE system state.
 *
 * ```
 * val status = ampere.status.snapshot()
 * println("${status.agents.size} agents, ${status.activeTickets} active tickets")
 * ```
 */
@Serializable
data class SystemSnapshot(
    val agents: List<AgentSnapshot>,
    val activeTickets: Int,
    val totalTickets: Int,
    val activeThreads: Int,
    val totalMessages: Int,
    val escalatedThreads: Int,
    val workspace: String?,
)
