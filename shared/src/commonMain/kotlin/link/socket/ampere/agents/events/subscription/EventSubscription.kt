package link.socket.ampere.agents.events.subscription

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.EventType

/**
 * Subscription returned by [link.socket.ampere.agents.events.bus.EventSerialBus.subscribe] used to cancel a subscription via [link.socket.ampere.agents.events.bus.EventSerialBus.unsubscribe].
 */
@Serializable
sealed class EventSubscription(
    open val agentId: AgentId,
) : Subscription {

    @Serializable
    data class ByEventClassType(
        val agentIdOverride: AgentId,
        val eventTypes: Set<EventType>,
    ) : EventSubscription(agentIdOverride) {

        override val subscriptionId: String =
            eventTypes.joinToString(",") + "/$agentId"
    }
}
