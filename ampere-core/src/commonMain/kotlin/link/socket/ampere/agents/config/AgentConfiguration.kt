package link.socket.ampere.agents.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.llm.BundledUpstreamLlmClient
import link.socket.ampere.llm.UpstreamLlmClient

@Serializable
data class AgentConfiguration(
    val agentDefinition: AgentDefinition,
    val aiConfiguration: AIConfiguration,
    val cognitiveConfig: CognitiveConfig = CognitiveConfig(),
    @Transient
    val llmProvider: LlmProvider? = null,
    @Transient
    val cognitiveRelay: CognitiveRelay? = null,
    /**
     * Outbound LLM-call seam. Defaults to [BundledUpstreamLlmClient] (the
     * pre-seam direct-call behavior). Embedded consumers (e.g. Socket)
     * override this to route LLM calls through their backend proxy.
     *
     * Note: a non-null [llmProvider] still short-circuits before the
     * upstream client runs.
     */
    @Transient
    val upstreamLlmClient: UpstreamLlmClient = BundledUpstreamLlmClient,
)
