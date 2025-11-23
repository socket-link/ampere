package link.socket.ampere.agents.events.subscription

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.EventClassType

/**
 * Subscription returned by [link.socket.ampere.agents.events.bus.EventBus.subscribe] used to cancel a subscription via [link.socket.ampere.agents.events.bus.EventBus.unsubscribe].
 */
@Serializable
sealed class EventSubscription(
    open val agentId: AgentId,
) : Subscription {

    @Serializable
    data class ByEventClassType(
        val agentIdOverride: AgentId,
        val eventClassTypes: Set<EventClassType>,
    ) : EventSubscription(agentIdOverride) {

        override val subscriptionId: String =
            eventClassTypes.joinToString(",") + "/$agentId"
    }
}
