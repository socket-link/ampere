package link.socket.kore.agents.events

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.kore.agents.core.AgentId
import link.socket.kore.agents.events.subscription.Subscription

@Serializable
sealed class NotificationEvent<S : Subscription>(
    val subscription: @Serializable S?,
) : Event {

    @Serializable
    data class ToAgent<S : Subscription>(
        val agentId: AgentId,
        val event: Event,
        val eventSubscription: S?,
    ) : NotificationEvent<S>(eventSubscription) {

        override val eventId: EventId = "${event.eventId}/{$agentId}"
        override val eventSource: EventSource = EventSource.Agent(agentId)
        override val timestamp: Instant = event.timestamp
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE
        override val urgency: Urgency = event.urgency

        companion object {
            private const val EVENT_TYPE = "NotificationToAgent"
            val EVENT_CLASS_TYPE = ToAgent::class to EVENT_TYPE
        }
    }

    @Serializable
    data class ToHuman<S : Subscription>(
        val event: Event,
        val eventSubscription: S?,
    ) : NotificationEvent<S>(eventSubscription) {

        override val eventId: EventId = "${event.eventId}/human"
        override val eventSource: EventSource = EventSource.Human
        override val timestamp: Instant = event.timestamp
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE
        override val urgency: Urgency = event.urgency

        companion object {
            private const val EVENT_TYPE = "NotificationToHuman"
            val EVENT_CLASS_TYPE = ToHuman::class to EVENT_TYPE
        }
    }
}
