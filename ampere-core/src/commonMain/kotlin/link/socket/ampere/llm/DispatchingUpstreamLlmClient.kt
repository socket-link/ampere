package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptor
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.executesLocally
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.routing.local.InferenceLocalityClassifier
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.api.AmpereStableApi
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * [UpstreamLlmClient] that dispatches each call to either a [LocalUpstreamLlmClient]
 * or the [BundledUpstreamLlmClient] (cloud) path, based on the relay-selected
 * configuration's [ModelDescriptor].
 *
 * This is the composition root of AMPR-203's execution surface. The relay has
 * already *selected* a configuration (post-`RouteSelected`); this client decides
 * *how* to execute it:
 *
 * - If the selected model's descriptor declares [CostPolicy.Free] **or** is
 *   [availabilityGated][ModelDescriptor.availabilityGated] — the markers of a
 *   local, on-device model — and a [localEngine] is bound, the call runs
 *   through the [LocalUpstreamLlmClient].
 * - If the descriptor designates the model local but **no** engine is bound,
 *   the call is refused with [LocalEngineNotBoundException] before anything is
 *   sent (AMPR-371). A configuration the catalog says runs on the device never
 *   goes out on a cloud transport.
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
 * `null` — no engine is bound — so every cloud-designated or undescribed call
 * routes to [bundled] exactly as before, and every local-designated call is
 * refused. Platform modules (`:ampere-relay-local-android` / `-apple`) supply a
 * real [LocalInferenceEngine]; tests supply a fake.
 *
 * Choosing a cloud model *instead* when the device cannot serve is not this
 * client's job: that is the relay's availability gate, which runs before the
 * call and reads [probeLocalCapacity]. By the time a local configuration
 * reaches [call], the decision to run on the device has been made and shown.
 *
 * ## Saying where a call ran
 *
 * This client is also the [InferenceLocalityClassifier] a surface should use
 * (AMPR-327). The catalog can say a model is *meant* to run locally; only the
 * client that executes it knows whether an engine is bound to run it on. With
 * none bound, a local-designated configuration cannot run anywhere this client
 * will send it, and [localityOf] reports it as [InferenceLocality.CLOUD] —
 * nothing not provably on the device is labelled "on-device".
 */
@AmpereStableApi
class DispatchingUpstreamLlmClient(
    private val registry: ModelDescriptorRegistry,
    private val localEngine: LocalInferenceEngine?,
    private val bundled: UpstreamLlmClient = BundledUpstreamLlmClient,
) : UpstreamLlmClient, InferenceLocalityClassifier {

    private val local: LocalUpstreamLlmClient? = localEngine?.let(::LocalUpstreamLlmClient)

    /**
     * Probes the bound [localEngine], if any, so a caller (e.g.
     * [link.socket.ampere.agents.domain.reasoning.AgentLLMService]) can populate
     * [link.socket.ampere.agents.domain.routing.RoutingContext.localCapacity]
     * before asking the relay to resolve — the relay's availability gate
     * (AMPR-207/225) only opens for a gated on-device rule when this snapshot
     * reports it available. Returns `null` when no engine is bound, exactly
     * like the existing `:ampere-core` (no platform module) default.
     */
    suspend fun probeLocalCapacity(): LocalCapacity? = localEngine?.probe()

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion {
        val localClient = local

        return if (isLocalModel(configuration.model.name)) {
            localClient?.call(request, configuration)
                ?: throw LocalEngineNotBoundException(
                    providerId = configuration.provider.id,
                    modelId = configuration.model.name,
                )
        } else {
            bundled.call(request, configuration)
        }
    }

    /**
     * Where [call] sends a request for [modelId]: [InferenceLocality.ON_DEVICE]
     * only when an engine is bound *and* the model's descriptor
     * [executesLocally][ModelDescriptor.executesLocally] — the same two
     * conditions [call] dispatches on, so the answer cannot disagree with what
     * ran. A local-designated model with no engine bound is a call [call]
     * refuses; it is reported [InferenceLocality.CLOUD] because nothing about
     * it is provably on the device. [providerId] is not consulted: dispatch
     * keys on the model.
     */
    override suspend fun localityOf(providerId: ProviderId, modelId: String): InferenceLocality =
        if (local != null && isLocalModel(modelId)) {
            InferenceLocality.ON_DEVICE
        } else {
            InferenceLocality.CLOUD
        }

    /**
     * Whether the catalog designates [modelName] for local execution: a free
     * (0-Watt) [CostPolicy] or a device-gated availability flag. A model with
     * no descriptor is not local.
     */
    private suspend fun isLocalModel(modelName: String): Boolean =
        registry.descriptorFor(modelName)?.executesLocally == true
}

/**
 * Raised by [DispatchingUpstreamLlmClient.call] when the resolved model's
 * descriptor [executesLocally][ModelDescriptor.executesLocally] but no
 * [LocalInferenceEngine] is bound to run it (AMPR-371). The request has not
 * been sent anywhere: a configuration the catalog designates for the device is
 * never handed to the cloud transport in its place.
 *
 * Propagates to [link.socket.ampere.agents.domain.reasoning.AgentLLMService],
 * which records it on `ProviderCallCompletedEvent.errorType` like any other
 * transport failure. If the step should have run in the cloud instead, that is
 * the relay's availability gate (which reads
 * [DispatchingUpstreamLlmClient.probeLocalCapacity]) — it decides before the
 * call, not after.
 */
@AmpereStableApi
class LocalEngineNotBoundException(
    val providerId: ProviderId,
    val modelId: String,
) : IllegalStateException(
    "Model '$modelId' (provider '$providerId') is designated for on-device " +
        "execution but no LocalInferenceEngine is bound on this host. The request " +
        "was not sent. Bind an engine, or route this call to a cloud model " +
        "before it reaches the transport.",
)
