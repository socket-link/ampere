package link.socket.ampere.agents.domain.routing

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.RoutingEvent
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
 */
class CognitiveRelayImpl(
    initialConfig: RelayConfig = RelayConfig(),
    private val eventBus: EventSerialBus? = null,
) : CognitiveRelay {

    private val logger: Logger = logWith("CognitiveRelay")
    private val mutex = Mutex()
    private var _config: RelayConfig = initialConfig

    override val config: RelayConfig
        get() = _config

    override suspend fun resolve(
        context: RoutingContext,
        fallbackConfiguration: AIConfiguration,
    ): AIConfiguration {
        val currentConfig = mutex.withLock { _config }

        val matchedRule = currentConfig.rules.firstOrNull { rule ->
            rule.matches(context)
        }

        val selectedConfig = matchedRule?.configuration
            ?: currentConfig.defaultConfiguration
            ?: fallbackConfiguration

        val ruleDescription = matchedRule?.describeRule() ?: "default"

        val decision = RoutingDecision(
            providerName = selectedConfig.provider.name,
            modelName = selectedConfig.model.name,
            matchedRule = ruleDescription,
        )

        logger.d {
            "[Relay] ${decision.providerName}/${decision.modelName} (rule: $ruleDescription)" +
                (context.phase?.let { " [${it.name}]" } ?: "")
        }

        emitRouteSelected(context, decision)

        return selectedConfig
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
}
