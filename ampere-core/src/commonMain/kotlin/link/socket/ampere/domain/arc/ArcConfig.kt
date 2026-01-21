package link.socket.ampere.domain.arc

import kotlinx.serialization.Serializable

@Serializable
data class ArcConfig(
    val name: String,
    val description: String? = null,
    val agents: List<ArcAgentConfig>,
    val orchestration: OrchestrationConfig = OrchestrationConfig(),
)
