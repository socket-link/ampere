package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.surface.AgentSurface
import link.socket.ampere.agents.events.surface.AgentSurfaceResponse
import link.socket.ampere.agents.events.surface.CorrelationId

/**
 * Bus-level events that carry [AgentSurface] traffic.
 *
 * Plugin code emits a [Requested] when it needs to render UI; the platform
 * renderer replies with a [Responded] carrying the same correlation id. The
 * matching [link.socket.ampere.agents.events.surface.awaitSurfaceResponse]
 * helper consumes [Responded] and resumes the Plugin coroutine.
 */
@Serializable
sealed interface AgentSurfaceEvent : Event {

    /** Echo of [AgentSurface.correlationId] for routing replies. */
    val correlationId: CorrelationId

    /** A Plugin is asking the platform to render an [AgentSurface]. */
    @Serializable
    data class Requested(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
        val surface: AgentSurface,
    ) : AgentSurfaceEvent {

        override val correlationId: CorrelationId = surface.correlationId

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String {
            val kind = when (surface) {
                is AgentSurface.Form -> "Form '${surface.title}'"
                is AgentSurface.Choice -> "Choice '${surface.title}'"
                is AgentSurface.Confirmation -> "Confirmation"
                is AgentSurface.Card -> "Card '${surface.title}'"
            }
            return "Surface request: $kind (corr=$correlationId) ${formatUrgency(urgency)} from ${formatSource(
                eventSource,
            )}"
        }

        companion object {
            const val EVENT_TYPE: EventType = "AgentSurfaceRequested"
        }
    }

    /** The platform renderer replying to a previous [Requested]. */
    @Serializable
    data class Responded(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.LOW,
        val response: AgentSurfaceResponse,
    ) : AgentSurfaceEvent {

        override val correlationId: CorrelationId = response.correlationId

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String {
            val outcome = when (response) {
                is AgentSurfaceResponse.Submitted -> "submitted"
                is AgentSurfaceResponse.Cancelled -> "cancelled"
                is AgentSurfaceResponse.TimedOut -> "timed out"
            }
            return "Surface response: $outcome (corr=$correlationId) ${formatUrgency(urgency)} from ${formatSource(
                eventSource,
            )}"
        }

        companion object {
            const val EVENT_TYPE: EventType = "AgentSurfaceResponded"
        }
    }
}
