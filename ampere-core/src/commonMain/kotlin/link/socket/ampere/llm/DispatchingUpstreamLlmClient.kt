package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptor
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptorRegistry
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * [UpstreamLlmClient] that dispatches each call to either a [LocalUpstreamLlmClient]
 * or the [BundledUpstreamLlmClient] (cloud) path, based on the relay-selected
 * configuration's [ProviderDescriptor].
 *
 * This is the composition root of AMPR-203's execution surface. The relay has
 * already *selected* a configuration (post-`RouteSelected`); this client decides
 * *how* to execute it:
 *
 * - If the selected provider's descriptor declares [CostPolicy.Free] **or** is
 *   [availabilityGated][ProviderDescriptor.availabilityGated] — the markers of a
 *   local, on-device provider — and a [localEngine] is bound, the call runs
 *   through the [LocalUpstreamLlmClient].
 * - Otherwise it runs through [bundled], i.e. the existing per-provider OpenAI
 *   client. This includes the case where no descriptor is registered, preserving
 *   today's cloud behavior for every existing provider.
 *
 * Because the descriptor lookup keys on the *resolved* configuration, this works
 * end-to-end with relay capability routing ([RoutingRule.ByCapability]
 * [link.socket.ampere.agents.domain.routing.RoutingRule.ByCapability]): the relay
 * picks the local config, and this client honors that selection at execution.
 *
 * ## Binding seam
 *
 * [localEngine] is the per-platform binding point. In `:ampere-core` it is
 * `null` — no engine is bound — so every call routes to [bundled] and behavior
 * is byte-equivalent to before. Platform modules
 * (`:ampere-relay-local-android` / `-apple`) supply a real
 * [LocalInferenceEngine]; tests supply a fake.
 */
@AmpereStableApi
class DispatchingUpstreamLlmClient(
    private val registry: ProviderDescriptorRegistry,
    localEngine: LocalInferenceEngine?,
    private val bundled: UpstreamLlmClient = BundledUpstreamLlmClient,
) : UpstreamLlmClient {

    private val local: LocalUpstreamLlmClient? = localEngine?.let(::LocalUpstreamLlmClient)

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion {
        val localClient = local
        val descriptor = registry.descriptorFor(configuration.provider.id)

        return if (localClient != null && descriptor != null && descriptor.prefersLocalExecution()) {
            localClient.call(request, configuration)
        } else {
            bundled.call(request, configuration)
        }
    }

    /**
     * Whether this descriptor designates a provider that should execute locally:
     * a free (0-Watt) cost policy or a device-gated availability flag.
     */
    private fun ProviderDescriptor.prefersLocalExecution(): Boolean =
        cost is CostPolicy.Free || availabilityGated
}
