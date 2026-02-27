package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.routing.RoutingDecision

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
}
