@file:Suppress("ktlint:standard:class-naming")

package link.socket.ampere.domain.ai.provider

import com.aallam.openai.client.OpenAI as Client
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.tool.AITool

private const val ID = "apple-on-device"
private const val NAME = "Apple Foundation Models (on-device)"

/**
 * Stand-in [AIProvider] for Rung 0 (AMPR-225): identifies the on-device
 * execution path in routing/cost/provenance, but [client] is never actually
 * called. Execution for this provider's models is dispatched to a bound
 * [link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine] by
 * [link.socket.ampere.llm.DispatchingUpstreamLlmClient] before the OpenAI-shaped
 * seam is ever reached, exactly like the [AIProvider_Anthropic]/local stand-in
 * used in `LocalInferenceRelayIntegrationTest`. [client] and [apiToken] exist
 * only to satisfy the [AIProvider] shape.
 */
data object AIProvider_OnDevice : AIProvider<AITool, AIModel_OnDevice> {

    override val id: ProviderId = ID
    override val name: String = NAME
    override val apiToken: String = ""
    override val availableModels: List<AIModel_OnDevice> = AIModel_OnDevice.ALL_MODELS

    override val client: Client by lazy {
        AIProvider.createClient(token = apiToken)
    }
}
