package link.socket.ampere.agents.domain.routing

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.routing.capability.CheapestCapableFirst
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptor
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.costPerWatt
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
 * When the first match is capability-based, selection is cost-aware (AMPR-210):
 * among all capable [RoutingRule.ByCapability] candidates the relay resolves to
 * the cheapest by cost-per-Watt (stable tie-break by `providerId`) and emits a
 * [RoutingEvent.RouteResolved]. Every other rule keeps pure first-match.
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

        // First-match wins (D2): the first rule whose predicate holds.
        val firstMatch = firstMatchingRule(currentConfig.rules, context)

        // Cost-aware selection generalises capability routing (AMPR-210): when
        // the first match is capability-based, choose the cheapest among *all*
        // capable candidates rather than strictly the first. Local-first is the
        // limiting case — a 0W local provider always wins on price. Non-capability
        // rules keep pure first-match, so existing routing is unchanged.
        val costSelection = (firstMatch as? RoutingRule.ByCapability)
            ?.let { selectCheapestCapable(currentConfig.rules, context) }

        val matchedRule = costSelection?.rule ?: firstMatch

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
        costSelection?.let { emitRouteResolved(context, decision, it) }

        return RoutingResolution(
            configuration = selectedConfig,
            reason = ruleDescription,
        )
    }

    /** The first rule whose registry-aware predicate matches [context], if any. */
    private suspend fun firstMatchingRule(
        rules: List<RoutingRule>,
        context: RoutingContext,
    ): RoutingRule? = rules.firstOrNull { it.matches(context, registry) }

    /**
     * Among the [RoutingRule.ByCapability] rules whose target provider satisfies
     * [context]'s requirement, the cheapest by cost-per-Watt (stable tie-break by
     * `providerId`). Returns `null` when no registry is wired or nothing matches.
     */
    private suspend fun selectCheapestCapable(
        rules: List<RoutingRule>,
        context: RoutingContext,
    ): CostSelection? {
        val registry = registry ?: return null

        val candidates = rules
            .filterIsInstance<RoutingRule.ByCapability>()
            .mapNotNull { rule ->
                if (!rule.matches(context, registry)) return@mapNotNull null
                val descriptor = registry.descriptorFor(rule.configuration.provider.id)
                    ?: return@mapNotNull null
                rule to descriptor
            }
            .sortedWith(compareBy(CheapestCapableFirst) { it.second })

        val (chosenRule, chosenDescriptor) = candidates.firstOrNull() ?: return null
        val runnerUp = candidates.getOrNull(1)

        return CostSelection(
            rule = chosenRule,
            chosen = chosenDescriptor,
            runnerUpProvider = runnerUp?.first?.configuration?.provider?.name,
            runnerUp = runnerUp?.second,
            candidateCount = candidates.size,
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

    private suspend fun emitRouteResolved(
        context: RoutingContext,
        decision: RoutingDecision,
        selection: CostSelection,
    ) {
        eventBus?.publish(
            RoutingEvent.RouteResolved(
                eventId = generateUUID("routing"),
                timestamp = Clock.System.now(),
                eventSource = context.agentId?.let { EventSource.Agent(it) }
                    ?: EventSource.Human,
                agentId = context.agentId,
                phase = context.phase,
                decision = decision,
                tier = selection.chosen.reasoning,
                estimatedWattCost = selection.chosen.costPerWatt,
                candidateCount = selection.candidateCount,
                runnerUpProvider = selection.runnerUpProvider,
                runnerUpWattCost = selection.runnerUp?.costPerWatt,
                savingsVsRunnerUp = selection.runnerUp
                    ?.let { it.costPerWatt - selection.chosen.costPerWatt },
            ),
        )
    }

    /**
     * The outcome of cost-aware selection: the winning [rule] and its
     * [chosen] descriptor, plus the [runnerUp] it beat (if any) for the
     * observable savings carried on [RoutingEvent.RouteResolved].
     */
    private data class CostSelection(
        val rule: RoutingRule,
        val chosen: ProviderDescriptor,
        val runnerUpProvider: String?,
        val runnerUp: ProviderDescriptor?,
        val candidateCount: Int,
    )
}
