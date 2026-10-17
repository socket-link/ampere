package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload
import link.socket.ampere.agents.domain.routing.CapabilityRoutingDefaults
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingEventSink
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.executesLocally
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalGenerationRequest
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceMonitor
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.domain.agent.bundled.OnDeviceAssistantAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * One on-device engine, bound to everything a model call needs to reach it and
 * to be seen reaching it (AMPR-374): the [relay] that routes to it, the
 * [registry] the relay and the executing [client] both read, and the [monitor]
 * that folds the resulting telemetry into [state].
 *
 * [OnDeviceInferenceSession] built exactly this wiring for a single prompt.
 * An Arc needs the same wiring handed to its `AmpereRuntime`, so it lives here,
 * where both can construct it — the session for `ask`, and
 * [ArcSession][link.socket.ampere.domain.arc.bridge.ArcSession] for every step
 * of every run. An Arc session's own display-text `ask` is a session built over
 * the Arc's binding (AMPR-461), not a second binding over the same engine: one
 * engine, one binding, one ledger. Nothing here decides *which* work runs on the
 * device: that is the routing floor's call, declared on the agent or the Arc
 * step, and this binding is only the road to the engine once a call is eligible.
 *
 * ## One registry
 *
 * The relay selects a model by its descriptor and the client executes by the
 * same descriptor. Giving them separate catalogs would let the two disagree
 * about whether a model is local — the relay choosing it as free while the
 * client, not knowing it, sent it to the cloud. One instance closes that.
 *
 * ## Every probe is folded
 *
 * The engine handed to the client is wrapped so that every availability probe
 * — the one `AgentLLMService` makes before each call included — is attributed
 * to the on-device provider, adopts the context window the engine reports,
 * and lands in [state]. The surface then knows the device could or could not
 * serve from the moment a call was considered, not only once routing events
 * arrive on the bus.
 *
 * ## On-device only unless given the cloud
 *
 * With no [cloud] transport the relay is given the on-device rules alone and
 * a cloud-resolved call has nothing to go out on: it fails with
 * [MissingUpstreamLlmClientException], having sent the prompt nowhere.
 * Supplying a transport is what opts the binding into the cloud (AMPR-236).
 *
 * @param engine The on-device engine to prefer.
 * @param cloud Transport for calls the device cannot serve, or null to stay
 *   on-device only.
 * @param routingEvents Where the relay's routing events go, or null to route
 *   silently. A door's [routingEventSink][link.socket.ampere.agents.domain.routing.routingEventSink]
 *   is the production value.
 * @param registry The model catalog the relay and the client share.
 * @param clock Stamps availability probes so a probe and an event can be
 *   ordered on the same timeline.
 */
class OnDeviceInferenceBinding(
    private val engine: LocalInferenceEngine,
    private val cloud: UpstreamLlmClient? = null,
    routingEvents: RoutingEventSink? = null,
    val registry: ModelDescriptorRegistry = InMemoryModelDescriptorRegistry(),
    clock: Clock = Clock.System,
) {
    /** True when no cloud transport was supplied: nothing this binding routes can leave the device. */
    val onDeviceOnly: Boolean
        get() = cloud == null

    /**
     * The on-device provider and model, as the bundled catalog names them. The
     * fallback configuration for an on-device-only caller, and the provider an
     * engine that does not name its own is attributed to.
     */
    val onDeviceConfiguration: AIConfiguration =
        OnDeviceAssistantAgent.suggestedAIConfigurationBuilder(AIConfigurationFactory)

    /** The provider id the bound engine's capacity is reported under. */
    val onDeviceProviderId: ProviderId
        get() = onDeviceConfiguration.provider.id

    /**
     * Executes the relay's choice: on the engine for the on-device model, on
     * [cloud] for anything else. Also the locality classifier a surface should
     * ask, since it alone knows an engine is bound.
     */
    val client: DispatchingUpstreamLlmClient = DispatchingUpstreamLlmClient(
        registry = registry,
        localEngine = ObservedEngine(),
        bundled = cloud ?: NoCloudTransport,
    )

    /** Routes each call to the cheapest capable model, over the catalog [client] executes against. */
    val relay: CognitiveRelay = CognitiveRelayImpl(
        initialConfig = RelayConfig(
            rules = if (cloud == null) {
                CapabilityRoutingDefaults.onDeviceCapabilityRules()
            } else {
                CapabilityRoutingDefaults.defaultCapabilityRules()
            },
        ),
        publish = routingEvents,
        registry = registry,
    )

    /** Holds [state] live; [follow] a bus to feed it the calls made there. */
    val monitor: OnDeviceInferenceMonitor = OnDeviceInferenceMonitor(classifier = client, clock = clock)

    /** When the on-device model is being used, and everything a surface needs to say so. */
    val state: StateFlow<OnDeviceInferenceState>
        get() = monitor.state

    /**
     * Ask the engine whether it can serve right now, fold the answer into
     * [state], and return it.
     *
     * The snapshot is attributed to the on-device provider when the engine does
     * not name one, and a reported context window replaces the catalog's
     * figure when they differ. An engine whose probe throws is reported unavailable
     * with the failure as the reason, so a caller asking "can the device
     * serve?" gets "no, and here is why" rather than an exception to handle.
     */
    suspend fun probe(): LocalCapacity {
        val capacity = try {
            engine.probe()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            LocalCapacity(
                available = false,
                reason = failure.message ?: failure::class.simpleName ?: OnDeviceInferenceMonitor.PROBE_FAILED_REASON,
            )
        }
        val attributed = capacity.attributed()
        adoptReportedContextWindow(attributed)
        monitor.record(attributed)
        return attributed
    }

    /**
     * Fold every model call and routing fallback dispatched on [bus] into
     * [state] until the calling coroutine is cancelled. Never returns normally;
     * see [OnDeviceInferenceMonitor.follow].
     */
    suspend fun follow(bus: EventSerialBus): Nothing = monitor.follow(bus)

    /**
     * Route against the context window the engine actually has, not the one
     * the catalog was seeded with.
     *
     * The bundled on-device descriptor carries 4,096 tokens, the figure the
     * live model reported on iOS 26.4.1, iOS 27.1 and macOS 27.0 (AMPR-373). A system whose model
     * has a different window says so in [LocalCapacity.maxContextTokens] —
     * Apple's engine reads `SystemLanguageModel.contextSize` at every probe —
     * and the relay decides whether a prompt fits from the descriptor, so the
     * figure has to land there to count. Only a local model's own descriptor is
     * touched, and only when the engine names both the model and the window.
     */
    private suspend fun adoptReportedContextWindow(capacity: LocalCapacity) {
        if (!capacity.available) return
        val modelId = capacity.modelId ?: return
        val window = capacity.maxContextTokens ?: return
        val descriptor = registry.descriptorFor(modelId) ?: return

        if (descriptor.executesLocally && descriptor.maxContextTokens != window) {
            registry.register(descriptor.copy(maxContextTokens = window))
        }
    }

    /**
     * An engine bound here *is* the on-device provider's engine, so a snapshot
     * that does not name its provider is attributed to it. The relay's
     * availability gate only opens for a snapshot whose provider matches the
     * model's, and an engine written against the text-only contract (no
     * provider id) would otherwise never be routed to.
     */
    private fun LocalCapacity.attributed(): LocalCapacity =
        if (providerId == null) copy(providerId = onDeviceProviderId) else this

    /**
     * The engine as the client sees it: generation passes straight through,
     * every probe goes through [probe] so it is attributed, adopted and folded,
     * and a token count that fails becomes "cannot count".
     *
     * Both generation forms are forwarded, and both counting forms. Left to the
     * interface's default, a request would be flattened here and reach [engine]
     * as a bare prompt or a bare string, and an engine that takes the request
     * apart would never see its instructions or its output budget, nor count
     * what it sends (AMPR-462).
     */
    private inner class ObservedEngine : LocalInferenceEngine {
        override suspend fun probe(): LocalCapacity = this@OnDeviceInferenceBinding.probe()

        override suspend fun generate(prompt: String): Result<String> = engine.generate(prompt)

        override suspend fun generate(request: LocalGenerationRequest): Result<String> = engine.generate(request)

        /**
         * The engine's own count, or null when it has none or fails to produce one
         * (AMPR-460). A counter that throws is an engine that cannot count: the
         * caller sizes by the estimate, and nothing about the call fails.
         */
        override suspend fun tokenCount(text: String): Int? =
            try {
                engine.tokenCount(text)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }

        /** [tokenCount] for a request, under the same rule: a counter that throws cannot count. */
        override suspend fun tokenCount(request: LocalGenerationRequest): Int? =
            try {
                engine.tokenCount(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }

        override suspend fun generateStructured(kind: EmissionKind, prompt: String): Result<EmissionPayload> =
            engine.generateStructured(kind, prompt)
    }

    /**
     * The cloud side of an on-device-only binding: there is none. A call that
     * reaches it fails instead of leaving the device.
     */
    private object NoCloudTransport : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = throw MissingUpstreamLlmClientException(agentName = null)
    }
}
