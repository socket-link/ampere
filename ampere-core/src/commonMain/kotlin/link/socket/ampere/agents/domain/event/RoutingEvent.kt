package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.routing.RoutingDecision
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning

/**
 * Events emitted by the CognitiveRelay for routing observability.
 *
 * These events make LLM routing decisions visible in the event stream,
 * enabling monitoring of which models are being used, when fallbacks
 * activate, and how routing rules affect model selection.
 */
@Serializable
sealed interface RoutingEvent : Event {

    /** The agent whose LLM call was routed. */
    val agentId: AgentId?

    /** The cognitive phase during routing, if any. */
    val phase: CognitivePhase?

    /**
     * Emitted when the relay routes an LLM call to a provider/model.
     */
    @Serializable
    @SerialName("RoutingEvent.RouteSelected")
    data class RouteSelected(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.LOW,
        override val agentId: AgentId?,
        override val phase: CognitivePhase?,
        val decision: RoutingDecision,
    ) : RoutingEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Route: ${decision.providerName}/${decision.modelName}")
            phase?.let { append(" [${it.name}]") }
            append(" (rule: ${decision.matchedRule})")
            if (decision.isFallback) append(" [FALLBACK]")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "RouteSelected"
        }
    }

    /**
     * Emitted when a routing fallback occurs (primary provider failed).
     */
    @Serializable
    @SerialName("RoutingEvent.RouteFallback")
    data class RouteFallback(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
        override val agentId: AgentId?,
        override val phase: CognitivePhase?,
        val failedProvider: String,
        val failedModel: String,
        val fallbackDecision: RoutingDecision,
        val failureReason: String,
    ) : RoutingEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Fallback: $failedProvider/$failedModel -> ")
            append("${fallbackDecision.providerName}/${fallbackDecision.modelName}")
            append(" ($failureReason)")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "RouteFallback"
        }
    }

    /**
     * Emitted when cost-aware selection chooses the cheapest capable provider
     * among equally-eligible candidates (AMPR-210). A sibling to [RouteFallback]:
     * where that records a failure-driven reroute, this records a price-driven
     * choice, making the savings observable in the event stream.
     *
     * Emitted only when capability routing admits at least one candidate; for a
     * single candidate, [runnerUpProvider] is null and there are no savings to
     * report.
     *
     * @property decision The chosen provider/model (the cheapest capable route).
     * @property tier The chosen provider's reasoning tier.
     * @property estimatedWattCost Chosen provider's cost-per-Watt (USD per 1000 tokens).
     * @property candidateCount How many capable providers were compared (`>= 1`).
     * @property runnerUpProvider Display name of the next-cheapest provider, if any.
     * @property runnerUpWattCost Runner-up's cost-per-Watt, if any.
     * @property savingsVsRunnerUp Per-Watt saving over the runner-up, if any (`>= 0`).
     */
    @Serializable
    @SerialName("RoutingEvent.RouteResolved")
    data class RouteResolved(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.LOW,
        override val agentId: AgentId?,
        override val phase: CognitivePhase?,
        val decision: RoutingDecision,
        val tier: RelativeReasoning,
        val estimatedWattCost: Double,
        val candidateCount: Int,
        val runnerUpProvider: String? = null,
        val runnerUpWattCost: Double? = null,
        val savingsVsRunnerUp: Double? = null,
    ) : RoutingEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Resolved: ${decision.providerName}/${decision.modelName}")
            append(" @ $estimatedWattCost USD/W [${tier.name}]")
            phase?.let { append(" [${it.name}]") }
            if (runnerUpProvider != null && savingsVsRunnerUp != null) {
                append(" vs $runnerUpProvider (saves $savingsVsRunnerUp USD/W)")
            }
            append(" among $candidateCount candidate(s)")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "RouteResolved"
        }
    }

    /**
     * Emitted when the routing requirement specifies a [CapabilityRung] floor
     * that no registered model meets. This is a terminal routing failure: no
     * [AIConfiguration] is produced and the resolution must not silently
     * downgrade to a sub-floor model.
     *
     * @property requestedFloor The minimum rung declared by the requirement.
     * @property bestAvailableRung The highest rung present in the registry among
     *   the configured [RoutingRule.ByCapability] rules, or null if none could be
     *   looked up. Surfaced for diagnostics so the operator can see the gap.
     */
    @Serializable
    @SerialName("RoutingEvent.RouteFloorUnmet")
    data class RouteFloorUnmet(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.HIGH,
        override val agentId: AgentId?,
        override val phase: CognitivePhase?,
        val requestedFloor: CapabilityRung,
        val bestAvailableRung: CapabilityRung?,
    ) : RoutingEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("FloorUnmet: requested rung ${requestedFloor.ordinal}")
            bestAvailableRung?.let { append(", best available ${it.ordinal}") }
                ?: append(", no capable model found")
            phase?.let { append(" [${it.name}]") }
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "RouteFloorUnmet"
        }
    }
}
