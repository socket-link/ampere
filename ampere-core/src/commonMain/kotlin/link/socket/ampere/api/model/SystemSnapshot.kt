package link.socket.ampere.api.model

import kotlinx.serialization.Serializable

/**
 * Point-in-time view of the entire AMPERE system state.
 */
@Serializable
data class SystemSnapshot(
    val activeTickets: Int,
    val totalTickets: Int,
    val activeThreads: Int,
    val totalMessages: Int,
    val escalatedThreads: Int,
    val workspace: String?,
)
