package link.socket.ampere.agents.domain.config

import kotlinx.serialization.Serializable
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration

@Serializable
data class AgentConfiguration(
    val agentDefinition: AgentDefinition,
    val aiConfiguration: AIConfiguration,
)
