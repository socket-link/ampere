package link.socket.ampere.agents.events

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AgentId
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

        companion object {
            const val EVENT_TYPE: EventType = "NotificationToHuman"
        }
    }
}
