package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.subscription.Subscription

@Serializable
sealed interface NotificationEvent<S : Subscription> : Event {

    val subscription: @Serializable S?

    @Serializable
    data class ToAgent<S : Subscription>(
        val agentId: AgentId,
        val event: Event,
        override val subscription: @Serializable S?,
    ) : NotificationEvent<S> {

        override val eventId: EventId = "${event.eventId}/{$agentId}"
        override val eventSource: EventSource = EventSource.Agent(agentId)
        override val timestamp: Instant = event.timestamp
        override val eventType: EventType = EVENT_TYPE
        override val urgency: Urgency = event.urgency

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Notification to $agentId: ${event.getSummary(formatUrgency, formatSource)}"

        companion object {
            const val EVENT_TYPE: EventType = "NotificationToAgent"
        }
    }

    @Serializable
    data class ToHuman<S : Subscription>(
        val event: Event,
        override val subscription: @Serializable S?,
    ) : NotificationEvent<S> {

        override val eventId: EventId = "${event.eventId}/human"
        override val eventSource: EventSource = EventSource.Human
        override val timestamp: Instant = event.timestamp
        override val eventType: EventType = EVENT_TYPE
        override val urgency: Urgency = event.urgency

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Notification to human: ${event.getSummary(formatUrgency, formatSource)}"

        companion object {
            const val EVENT_TYPE: EventType = "NotificationToHuman"
        }
    }
}
