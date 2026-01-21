package link.socket.ampere.domain.arc

import kotlinx.serialization.Serializable

@Serializable
data class ArcAgentConfig(
    val role: String,
    val sparks: List<String> = emptyList(),
)
