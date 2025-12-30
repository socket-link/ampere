package link.socket.ampere.agents.health

import kotlinx.serialization.Serializable

@Serializable
sealed interface SystemHealth {
    val status: String
    val isAvailable: Boolean
}
