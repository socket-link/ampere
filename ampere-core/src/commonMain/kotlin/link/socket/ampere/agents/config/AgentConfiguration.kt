package link.socket.ampere.agents.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.llm.BundledUpstreamLlmClient
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.llm.decide.UpstreamDecisionClient

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
     * Outbound LLM-call seam. Embedded consumers (e.g. Socket) set this to
     * route LLM calls through their backend proxy; callers that want the
     * direct per-provider call set [BundledUpstreamLlmClient] explicitly.
     *
     * `null` means no transport has been chosen. Reaching
     * [AgentLLMService.call][link.socket.ampere.agents.domain.reasoning.AgentLLMService.call]
     * in that state throws
     * [MissingUpstreamLlmClientException][link.socket.ampere.llm.MissingUpstreamLlmClientException]
     * rather than silently egressing to the provider (AMPR-236).
     *
     * Note: a non-null [llmProvider] still short-circuits before the
     * upstream client runs, so it needs no transport.
     */
    @Transient
    val upstreamLlmClient: UpstreamLlmClient? = null,
    /**
     * Outbound decision-call seam (AMPR-384, J1): the sibling of
     * [upstreamLlmClient] for the Decide call kind. `null` means no decision
     * transport has been chosen, and
     * [AgentReasoning.decide][link.socket.ampere.agents.domain.reasoning.AgentReasoning.decide]
     * throws
     * [MissingUpstreamDecisionClientException][link.socket.ampere.llm.decide.MissingUpstreamDecisionClientException]
     * rather than falling back to the model-backed adapter. Independent of the
     * chat seam: an agent may carry either, both, or neither.
     */
    @Transient
    val upstreamDecisionClient: UpstreamDecisionClient? = null,
)
