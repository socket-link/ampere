package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.reasoning.PromptTokenEstimator
import link.socket.ampere.agents.domain.routing.CapabilityRoutingDefaults
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingFloorUnmetException
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.executesLocally
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceMonitor
import link.socket.ampere.agents.domain.routing.local.OnDeviceInferenceState
import link.socket.ampere.agents.domain.routing.routingEventSink
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.OnDeviceAssistantAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * One answer from [OnDeviceInferenceSession.ask], and where it was produced.
 *
 * @property text The model's response.
 * @property locality Whether the prompt stayed on the device. Decided by the
 *   client that executed the call, not by the route the relay picked.
 * @property providerId The provider that served the call.
 * @property modelId The model that served the call.
 * @property routingReason The relay rule that selected [modelId].
 * @property latencyMs Wall-clock time the generation took.
 * @property runId The run the call's events are stored under, for a caller that
 *   wants to read them back.
 */
data class LocalFirstAnswer(
    val text: String,
    val locality: InferenceLocality,
    val providerId: ProviderId,
    val modelId: String,
    val routingReason: String,
    val latencyMs: Long,
    val runId: String,
)

/**
 * What [OnDeviceInferenceSession.ask] hands back: an answer, or the reason there
 * is none.
 *
 * A value rather than a thrown exception so a surface can switch on it, and so
 * the same type crosses the Objective-C boundary intact (an undeclared Kotlin
 * exception terminates a Swift caller).
 */
sealed interface LocalFirstOutcome {

    /** The call produced [answer]. */
    data class Answered(val answer: LocalFirstAnswer) : LocalFirstOutcome

    /**
     * The call produced nothing.
     *
     * @property reason Which of the ways a local-first call can fail this was.
     * @property detail The underlying message, for a log or a disclosure row.
     */
    data class Failed(
        val reason: FailureReason,
        val detail: String? = null,
    ) : LocalFirstOutcome

    enum class FailureReason {
        /** The prompt was blank; nothing was sent anywhere. */
        EMPTY_PROMPT,

        /**
         * The device cannot serve and the session has no cloud transport to
         * fall back to. Nothing was sent anywhere.
         */
        ON_DEVICE_UNAVAILABLE,

        /**
         * No model this session can reach has a context window large enough for
         * the prompt. Nothing was sent anywhere.
         */
        PROMPT_TOO_LARGE,

        /** The on-device engine was given the prompt and did not complete it. */
        ON_DEVICE_GENERATION_FAILED,

        /** A cloud provider was given the prompt and did not complete it. */
        CLOUD_GENERATION_FAILED,
    }
}

/**
 * Local-first inference with its own visibility: ask a question, have it
 * answered on the device when the device can, and watch that happen (AMPR-327).
 *
 * This is the smallest complete path through Ampere's on-device machinery, and
 * it takes the same road an agent's call does — nothing here is a shortcut:
 *
 * 1. [ask] calls [AgentLLMService], the single entry point for model calls.
 * 2. The service probes the bound [LocalInferenceEngine] and hands the result to
 *    the [CognitiveRelay], which picks the cheapest model that satisfies the
 *    call. The on-device model is free, so when the engine reports it available
 *    and the prompt fits its context window, it wins.
 * 3. [DispatchingUpstreamLlmClient] executes the choice: on the engine for the
 *    on-device model, on the cloud transport for anything else.
 * 4. The service publishes `ProviderCallStarted` / `ProviderCallCompleted`
 *    through the door, persisted and then dispatched, and the relay publishes
 *    its routing events the same way. Each call is tagged
 *    [CognitivePhase.EXECUTE] and carries its own run id.
 * 5. [state] folds those events. A surface collects it.
 *
 * ## On-device only, by default
 *
 * With no [cloud] transport the session can only ever run on the device: the
 * relay is given the on-device rules alone, and a cloud-resolved call has no
 * client to go out on. [ask] then fails with
 * [LocalFirstOutcome.FailureReason.ON_DEVICE_UNAVAILABLE] when the engine cannot
 * serve, having sent the prompt nowhere. Supplying a transport is what opts a
 * session into the cloud, which is the same rule as every other Ampere call
 * (AMPR-236).
 *
 * ## No silent fallback after a failure
 *
 * Availability is decided before the call. If the engine reported itself
 * available and then fails to generate, the failure is returned — the prompt is
 * not quietly re-sent to the cloud. A person who was shown "on-device" must not
 * find out afterwards that their prompt left.
 *
 * @param engine The on-device engine to prefer.
 * @param eventApi The door this session's telemetry is published through.
 * @param scope Runs the bus subscription behind [state]. Cancelled by [close]
 *   only when this session created it (see [Companion.create]).
 * @param cloud Transport for calls the device cannot serve, or null to keep the
 *   session on-device only.
 * @param registry The model catalog the relay and the dispatching client share.
 *   One instance, so routing and execution cannot disagree about a model.
 * @param maxOutputTokens Output budget requested from the model, and reserved
 *   out of its context window when deciding whether a prompt fits.
 * @param clock Stamps availability probes; defaults to the door's clock so a
 *   probe and an event are ordered on the same timeline.
 */
class OnDeviceInferenceSession(
    private val engine: LocalInferenceEngine,
    private val eventApi: AgentEventApi,
    private val scope: CoroutineScope,
    private val cloud: UpstreamLlmClient? = null,
    private val registry: ModelDescriptorRegistry = InMemoryModelDescriptorRegistry(),
    private val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    clock: Clock = eventApi.clock,
) {
    private val client = DispatchingUpstreamLlmClient(
        registry = registry,
        localEngine = engine,
        bundled = cloud ?: NoCloudTransport,
    )

    private val relay: CognitiveRelay = CognitiveRelayImpl(
        initialConfig = RelayConfig(
            rules = if (cloud == null) {
                CapabilityRoutingDefaults.onDeviceCapabilityRules()
            } else {
                CapabilityRoutingDefaults.defaultCapabilityRules()
            },
        ),
        publish = eventApi.routingEventSink(),
        registry = registry,
    )

    private val onDeviceConfiguration: AIConfiguration =
        OnDeviceAssistantAgent.suggestedAIConfigurationBuilder(AIConfigurationFactory)

    private val service = AgentLLMService(
        agentConfiguration = AgentConfiguration(
            agentDefinition = OnDeviceAssistantAgent,
            aiConfiguration = onDeviceConfiguration,
            cognitiveRelay = relay,
            upstreamLlmClient = client,
        ),
        eventApi = eventApi,
    )

    private val monitor = OnDeviceInferenceMonitor(classifier = client, clock = clock)

    /** Set only by [Companion.create]; the scope this session must clean up after itself. */
    private var ownedScope: CoroutineScope? = null

    /** When the on-device model is being used, and everything a surface needs to say so. */
    val state: StateFlow<OnDeviceInferenceState> = monitor.state

    init {
        // UNDISPATCHED so the bus subscriptions are registered before the constructor
        // returns: a call made on the very next line is already being watched.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            monitor.follow(eventApi.eventSerialBus)
        }
    }

    /**
     * Ask the engine whether it can serve right now, and fold the answer into
     * [state]. Call on launch, and again whenever the surface comes forward —
     * a model that was still downloading a minute ago may be ready now.
     */
    suspend fun refreshAvailability(): LocalCapacity =
        monitor.probe(engine).attributed().also { adoptReportedContextWindow(it) }

    /**
     * Route against the context window the engine actually has, not the one
     * the catalog was seeded with.
     *
     * The bundled on-device descriptor carries a provisional 4,096 tokens. An
     * engine that knows better says so in [LocalCapacity.maxContextTokens] —
     * Apple's reports the running model's real window from iOS 27 — and the
     * relay decides whether a prompt fits from the descriptor, so the figure
     * has to land there to count. Only a local model's own descriptor is
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
     * An engine bound to this session *is* the on-device provider's engine, so
     * a snapshot that does not name its provider is attributed to it. The
     * relay's availability gate only opens for a snapshot whose provider matches
     * the model's, and an engine written against the text-only contract (no
     * provider id) would otherwise never be routed to.
     */
    private fun LocalCapacity.attributed(): LocalCapacity =
        if (providerId == null) copy(providerId = onDeviceConfiguration.provider.id) else this

    /**
     * Answer [prompt], on the device when the device can.
     *
     * Never throws for a model or routing failure; see [LocalFirstOutcome.Failed].
     * Cancellation propagates as cancellation.
     *
     * @param systemMessage Context for the model. Defaults to the
     *   [OnDeviceAssistantAgent] prompt.
     */
    suspend fun ask(
        prompt: String,
        systemMessage: String = OnDeviceAssistantAgent.prompt,
    ): LocalFirstOutcome {
        if (prompt.isBlank()) {
            return LocalFirstOutcome.Failed(LocalFirstOutcome.FailureReason.EMPTY_PROMPT)
        }

        val capacity = refreshAvailability()
        if (cloud == null && !capacity.available) {
            return LocalFirstOutcome.Failed(
                reason = LocalFirstOutcome.FailureReason.ON_DEVICE_UNAVAILABLE,
                detail = capacity.reason,
            )
        }

        val runId = generateUUID(RUN_ID_PREFIX)
        val contextTokens = PromptTokenEstimator.estimateInputTokens(listOf(systemMessage, prompt)) +
            maxOutputTokens

        return try {
            val result = service.callDetailed(
                prompt = prompt,
                systemMessage = systemMessage,
                maxTokens = maxOutputTokens,
                routingContext = RoutingContext(
                    // Answering the prompt is the step being executed. Tagged so the call is
                    // bucketed in the trace instead of filed under an unknown phase: a model
                    // call with no phase is invisible to the read model PROPEL reports against.
                    phase = CognitivePhase.EXECUTE,
                    agentId = eventApi.agentId,
                    agentRole = OnDeviceAssistantAgent.name,
                    workflowId = runId,
                    requirements = CapabilityRequirement(
                        inputs = SupportedInputs.TEXT,
                        // Input plus the output budget: a model whose window cannot hold
                        // both is not a candidate, so an over-long prompt routes to a
                        // larger model (or fails cleanly) instead of overflowing the
                        // on-device one mid-generation.
                        minContextTokens = contextTokens,
                    ),
                    localCapacity = capacity,
                ),
            )
            LocalFirstOutcome.Answered(
                LocalFirstAnswer(
                    text = result.text,
                    locality = client.localityOf(result.providerId, result.modelId),
                    providerId = result.providerId,
                    modelId = result.modelId,
                    routingReason = result.routingReason,
                    latencyMs = result.latencyMs,
                    runId = runId,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unmet: RoutingFloorUnmetException) {
            // The only requirement this session adds beyond the agent's Rung 0 floor is the
            // context window, so a route that cannot be satisfied is a prompt that does not fit.
            LocalFirstOutcome.Failed(
                reason = LocalFirstOutcome.FailureReason.PROMPT_TOO_LARGE,
                detail = "The prompt needs about $contextTokens tokens of context. ${unmet.message.orEmpty()}".trim(),
            )
        } catch (local: LocalInferenceException) {
            LocalFirstOutcome.Failed(
                reason = LocalFirstOutcome.FailureReason.ON_DEVICE_GENERATION_FAILED,
                detail = local.rootCauseMessage(),
            )
        } catch (noTransport: MissingUpstreamLlmClientException) {
            // Reached only if the relay resolved a cloud model for an on-device-only
            // session, which its rule set is built to prevent. Reported, never egressed.
            LocalFirstOutcome.Failed(
                reason = LocalFirstOutcome.FailureReason.ON_DEVICE_UNAVAILABLE,
                detail = capacity.reason,
            )
        } catch (failure: Throwable) {
            LocalFirstOutcome.Failed(
                reason = if (cloud == null) {
                    LocalFirstOutcome.FailureReason.ON_DEVICE_GENERATION_FAILED
                } else {
                    LocalFirstOutcome.FailureReason.CLOUD_GENERATION_FAILED
                },
                detail = failure.rootCauseMessage() ?: failure::class.simpleName,
            )
        }
    }

    /**
     * The message of the failure that started the chain — what the engine or the
     * provider actually said. Not this throwable's own message: a wrapper says
     * "the engine failed", and coroutines may wrap the wrapper again when an
     * exception crosses a dispatcher, so the top of the chain is the least
     * informative part of it.
     */
    private fun Throwable.rootCauseMessage(): String? =
        generateSequence(this) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .mapNotNull { it.message }
            .lastOrNull()

    /**
     * Release the scope this session made for itself in [Companion.create],
     * ending the bus subscription behind [state]. A no-op for a session built on
     * a caller-supplied scope — that lifetime is not ours to end.
     */
    fun close() {
        ownedScope?.cancel()
        ownedScope = null
    }

    /**
     * The cloud side of an on-device-only session: there is none. A call that
     * reaches it fails instead of leaving the device.
     */
    private object NoCloudTransport : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = throw MissingUpstreamLlmClientException(OnDeviceAssistantAgent.name)
    }

    companion object {
        /** Identity the session publishes its telemetry under. */
        const val AGENT_ID: String = "on-device-assistant"

        /**
         * Default output budget. Half of the smallest on-device context window
         * Ampere knows of (4,096 tokens), leaving the other half for the prompt.
         */
        const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 2_048

        private const val RUN_ID_PREFIX = "on-device-ask"

        /** Bound on the cause chain walked for a failure message; guards a cyclic chain. */
        private const val MAX_CAUSE_DEPTH = 16

        /**
         * Build an on-device-only session that owns its scope and its bus.
         *
         * Every parameter is something Swift can construct: the engine is a
         * Swift class adapted with `toLocalInferenceEngine()`, and the database
         * comes from the platform's driver factory. The coroutine machinery
         * stays on the Kotlin side of the line, as it does for
         * [ArcSession][link.socket.ampere.domain.arc.bridge.ArcSession].
         *
         * The caller owns the returned session and must [close] it.
         *
         * @param engine The on-device engine to run on.
         * @param database Where the session's events are persisted. Its schema
         *   must already be current, as every platform's driver factory leaves it.
         */
        fun create(
            engine: LocalInferenceEngine,
            database: Database,
        ): OnDeviceInferenceSession = create(engine = engine, database = database, cloud = null)

        /**
         * [create], with a [cloud] transport for calls the device cannot serve.
         *
         * A separate overload rather than a defaulted parameter: the
         * Objective-C export drops Kotlin defaults.
         */
        fun create(
            engine: LocalInferenceEngine,
            database: Database,
            cloud: UpstreamLlmClient?,
        ): OnDeviceInferenceSession {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventSerialBus(scope = scope)
            val eventApi = AgentEventApi(
                agentId = AGENT_ID,
                eventRepository = EventRepository(DEFAULT_JSON, scope, database),
                eventSerialBus = bus,
            )
            val session = OnDeviceInferenceSession(
                engine = engine,
                eventApi = eventApi,
                cloud = cloud,
                scope = scope,
            )
            session.ownedScope = scope
            return session
        }
    }
}
