@file:Suppress("ktlint:standard:class-naming")

package link.socket.ampere.domain.ai.provider

import com.aallam.openai.client.OpenAI as Client
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.tool.AITool

private const val ID = "apple-on-device"
private const val NAME = "Apple Foundation Models (on-device)"

/**
 * Stand-in [AIProvider] for Rung 0 (AMPR-225): identifies the on-device
 * execution path in routing/cost/provenance. Execution for this provider's
 * models is dispatched to a bound
 * [link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine] by
 * [link.socket.ampere.llm.DispatchingUpstreamLlmClient] before the OpenAI-shaped
 * seam is ever reached, exactly like the [AIProvider_Anthropic]/local stand-in
 * used in `LocalInferenceRelayIntegrationTest`. [apiToken] exists only to
 * satisfy the [AIProvider] shape.
 *
 * [client] has no host to point at. Reading it throws
 * [OnDeviceProviderHasNoClientException] (AMPR-371): an earlier version built a
 * default client, which meant any path that reached it POSTed the prompt to
 * `api.openai.com` with an empty bearer token and got a 401 back — after the
 * prompt had left the device. Failing on use is the only way to guarantee no
 * caller, present or future, can egress through this provider.
 */
data object AIProvider_OnDevice : AIProvider<AITool, AIModel_OnDevice> {

    override val id: ProviderId = ID
    override val name: String = NAME
    override val apiToken: String = ""
    override val availableModels: List<AIModel_OnDevice> = AIModel_OnDevice.ALL_MODELS

    override val client: Client
        get() = throw OnDeviceProviderHasNoClientException()
}
