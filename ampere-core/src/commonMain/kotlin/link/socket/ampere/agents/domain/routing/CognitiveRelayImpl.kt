package link.socket.ampere.agents.domain.routing

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptorRegistry
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.util.logWith

/**
 * Default implementation of [CognitiveRelay].
 *
 * Evaluates routing rules in order (first-match-wins), emits
 * [RoutingEvent]s through the [EventSerialBus], and supports
 * hot-swapping configuration via [updateConfig].
 *
 * Thread-safe: config updates are guarded by a [Mutex].
 *
 * @param initialConfig The initial relay configuration.
 * @param eventBus Optional EventSerialBus for routing event emission.
 * @param registry Optional provider descriptor registry consulted by
 *   capability-based rules (e.g. [RoutingRule.ByCapability]). When null,
 *   capability rules never match and routing behaves exactly as before.
 */
class CognitiveRelayImpl(
    initialConfig: RelayConfig = RelayConfig(),
    private val eventBus: EventSerialBus? = null,
    private val registry: ProviderDescriptorRegistry? = null,
) : CognitiveRelay {

    private val logger: Logger = logWith("CognitiveRelay")
    private val mutex = Mutex()
    private var _config: RelayConfig = initialConfig

    override val config: RelayConfig
        get() = _config

    override suspend fun resolve(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): AIConfiguration =
        resolveWithMetadata(context, fallbackConfiguration).configuration

    override suspend fun resolveWithMetadata(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): RoutingResolution {
        val currentConfig = mutex.withLock { _config }

        // First-match over the ordered rules, but a capable local provider whose
        // availability gate is closed is recorded as a skip (not a match) so we
        // can fall through to the grid and emit RouteFallback for the first skip.
        var matchedRule: RoutingRule? = null
        var skippedConfig: AIConfiguration? = null
        var skipReason: String? = null

        for (rule in currentConfig.rules) {
            if (rule is RoutingRule.ByCapability) {
                when (val evaluation = rule.evaluate(context, registry)) {
                    CapabilityEvaluation.Matched -> {
                        matchedRule = rule
                        break
                    }
                    is CapabilityEvaluation.Skipped -> {
                        if (skippedConfig == null) {
                            skippedConfig = rule.configuration
                            skipReason = evaluation.reason
                        }
                    }
                    CapabilityEvaluation.NoMatch -> Unit
                }
            } else if (rule.matches(context, registry)) {
                matchedRule = rule
                break
            }
        }

        val selectedConfig = matchedRule?.configuration
            ?: currentConfig.defaultConfiguration
            ?: fallbackConfiguration

        val ruleDescription = matchedRule?.describeRule() ?: "default"
        val isFallback = skippedConfig != null

        val decision = RoutingDecision(
            providerName = selectedConfig.provider.name,
            modelName = selectedConfig.model.name,
            matchedRule = ruleDescription,
            isFallback = isFallback,
        )

        logger.d {
            "[Relay] ${decision.providerName}/${decision.modelName} (rule: $ruleDescription)" +
                (context.phase?.let { " [${it.name}]" } ?: "")
        }

        // The fallback causally precedes the selection it forced; emit it first.
        if (skippedConfig != null && skipReason != null) {
            emitRouteFallback(context, skippedConfig, decision, skipReason)
        }
        emitRouteSelected(context, decision)

        return RoutingResolution(
            configuration = selectedConfig,
            reason = ruleDescription,
        )
    }

    override suspend fun updateConfig(newConfig: RelayConfig) {
        mutex.withLock {
            _config = newConfig
        }
        logger.d { "[Relay] Config updated: ${newConfig.rules.size} rules" }
    }

    private suspend fun emitRouteSelected(
        context: RoutingContext,
        decision: RoutingDecision,
    ) {
        eventBus?.publish(
            RoutingEvent.RouteSelected(
                eventId = generateUUID("routing"),
                timestamp = Clock.System.now(),
                eventSource = context.agentId?.let { EventSource.Agent(it) }
                    ?: EventSource.Human,
                agentId = context.agentId,
                phase = context.phase,
                decision = decision,
            ),
        )
    }

    /**
     * Emits [RoutingEvent.RouteFallback] for a capable local provider that was
     * skipped because its availability gate was closed, reporting the provider
     * and model that lost the route and the [fallbackDecision] that replaced it.
     */
    private suspend fun emitRouteFallback(
        context: RoutingContext,
        skippedConfig: AIConfiguration,
        fallbackDecision: RoutingDecision,
        failureReason: String,
    ) {
        eventBus?.publish(
            RoutingEvent.RouteFallback(
                eventId = generateUUID("routing"),
                timestamp = Clock.System.now(),
                eventSource = context.agentId?.let { EventSource.Agent(it) }
                    ?: EventSource.Human,
                agentId = context.agentId,
                phase = context.phase,
                failedProvider = skippedConfig.provider.id,
                failedModel = skippedConfig.model.name,
                fallbackDecision = fallbackDecision,
                failureReason = failureReason,
            ),
        )
    }
}
