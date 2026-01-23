package link.socket.ampere.agents.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.llm.LlmProvider

@Serializable
data class AgentConfiguration(
    val agentDefinition: AgentDefinition,
    val aiConfiguration: AIConfiguration,
    val cognitiveConfig: CognitiveConfig = CognitiveConfig(),
    @Transient
    val llmProvider: LlmProvider? = null,
)
