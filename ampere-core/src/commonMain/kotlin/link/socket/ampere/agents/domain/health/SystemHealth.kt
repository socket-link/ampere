package link.socket.ampere.agents.domain.health

import kotlinx.serialization.Serializable

@Serializable
sealed interface SystemHealth {
    val status: String
    val isAvailable: Boolean
}
