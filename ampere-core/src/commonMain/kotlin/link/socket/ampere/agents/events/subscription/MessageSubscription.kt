package link.socket.ampere.agents.events.subscription

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageThreadId

/**
 * Subscription returned by [link.socket.ampere.agents.events.bus.EventSerialBus.subscribe] used to cancel a subscription via [link.socket.ampere.agents.events.bus.EventSerialBus.unsubscribe].
 */
@Serializable
sealed class MessageSubscription(
    open val agentId: AgentId,
) : Subscription {

    @Serializable
    data class ByType(
        val agentIdOverride: AgentId,
        val eventTypes: Set<EventType>,
    ) : MessageSubscription(agentIdOverride) {

        override val subscriptionId: String =
            eventTypes.joinToString(",") + "/$agentId"
    }

    @Serializable
    data class ByChannels(
        val agentIdOverride: AgentId,
        val channels: Set<MessageChannel>,
    ) : MessageSubscription(agentIdOverride) {

        override val subscriptionId: String =
            channels.joinToString(",") + "/$agentId"
    }

    @Serializable
    data class ByThreads(
        val agentIdOverride: AgentId,
        val threadIds: Set<MessageThreadId>,
    ) : MessageSubscription(agentIdOverride) {

        override val subscriptionId: String =
            threadIds.joinToString(",") + "/$agentId"
    }
}
