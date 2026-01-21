package link.socket.ampere.domain.arc

import kotlinx.serialization.Serializable

@Serializable
data class OrchestrationConfig(
    val type: OrchestrationType = OrchestrationType.SEQUENTIAL,
    val order: List<String> = emptyList(),
)
