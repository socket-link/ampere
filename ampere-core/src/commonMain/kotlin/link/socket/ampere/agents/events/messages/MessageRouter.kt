package link.socket.ampere.agents.events.messages

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.escalation.EscalationEventHandler
import link.socket.ampere.agents.events.subscription.MessageSubscription

/**
 * Fans message events out to the agents subscribed to their channel as
 * [NotificationEvent.ToAgent]s.
 *
 * Every notification is published through the door ([AgentEventApi.publish], F1) with
 * `causedBy` set to the message event it notifies about (F2). The handlers here return `Unit`,
 * so a notification that fails to persist is logged by the door and not dispatched; there is
 * no caller to return it to.
 */
class MessageRouter(
    private val messageApi: AgentMessageApi,
    private val escalationEventHandler: EscalationEventHandler,
    private val eventApi: AgentEventApi,
) {
    private val messagesByChannelsSubscriptions = mutableMapOf<AgentId, MessageSubscription.ByChannels>()
    private val messagesByThreadsSubscriptions = mutableMapOf<AgentId, MessageSubscription.ByThreads>()
    private val messagesByTypeSubscriptions = mutableMapOf<AgentId, MessageSubscription.ByType>()

    fun startRouting() {
        MessageChannel
            .ALL_PUBLIC_CHANNELS
            .forEach { channel ->
                getSubscribedAgents(channel).forEach { agentId ->
                    messageApi.onThreadCreated { event, subscription ->
                        NotificationEvent.ToAgent(
                            agentId = agentId,
                            event = event,
                            subscription = subscription,
                        ).let { notificationEvent -> eventApi.publish(notificationEvent, causedBy = event.eventId) }
                    }

                    messageApi.onChannelMessagePosted(channel) { event, subscription ->
                        NotificationEvent.ToAgent(
                            agentId = agentId,
                            event = event,
                            subscription = subscription,
                        ).let { notificationEvent -> eventApi.publish(notificationEvent, causedBy = event.eventId) }
                    }

                    messageApi.onThreadStatusChanged { event, subscription ->
                        NotificationEvent.ToAgent(
                            agentId = agentId,
                            event = event,
                            subscription = subscription,
                        ).let { notificationEvent -> eventApi.publish(notificationEvent, causedBy = event.eventId) }
                    }
                }
            }

        messageApi.onEscalationRequested { event, subscription ->
            escalationEventHandler.invoke(event, subscription)
        }
    }

    fun subscribeToMessageType(
        agentId: AgentId,
        eventType: EventType,
    ): MessageSubscription.ByType {
        val updatedSubscription = messagesByTypeSubscriptions[agentId]?.let { existingSubscription ->
            val newTypes = existingSubscription.eventTypes.plus(eventType)
            MessageSubscription.ByType(agentId, newTypes)
        } ?: MessageSubscription.ByType(agentId, setOf(eventType))

        messagesByTypeSubscriptions[agentId] = updatedSubscription

        return updatedSubscription
    }

    fun subscribeToChannel(
        agentId: AgentId,
        channel: MessageChannel,
    ): MessageSubscription.ByChannels {
        val updatedSubscription = messagesByChannelsSubscriptions[agentId]?.let { existingSubscription ->
            val newChannels = existingSubscription.channels.plus(channel)
            MessageSubscription.ByChannels(agentId, newChannels)
        } ?: MessageSubscription.ByChannels(agentId, setOf(channel))

        messagesByChannelsSubscriptions[agentId] = updatedSubscription

        return updatedSubscription
    }

    fun unsubscribeFromChannel(
        subscription: MessageSubscription.ByChannels,
        channel: MessageChannel,
    ): MessageSubscription.ByChannels {
        val updatedSubscription = subscription.copy(
            channels = subscription.channels - channel,
        )

        messagesByChannelsSubscriptions[subscription.agentId] = updatedSubscription
        return updatedSubscription
    }

    fun subscribeToThread(
        agentId: AgentId,
        threadId: MessageThreadId,
    ): MessageSubscription.ByThreads {
        val updatedSubscription = messagesByThreadsSubscriptions[agentId]?.let { existingSubscription ->
            val newThreadIds = existingSubscription.threadIds.plus(threadId)
            MessageSubscription.ByThreads(agentId, newThreadIds)
        } ?: MessageSubscription.ByThreads(agentId, setOf(threadId))

        messagesByThreadsSubscriptions[agentId] = updatedSubscription

        return updatedSubscription
    }

    fun unsubscribeFromThread(
        subscription: MessageSubscription.ByThreads,
        threadId: MessageThreadId,
    ): MessageSubscription.ByThreads {
        val updatedThreadIds = subscription.threadIds.minus(threadId)

        val updatedSubscription = if (updatedThreadIds.isEmpty()) {
            messagesByThreadsSubscriptions.remove(subscription.agentId)
                ?: MessageSubscription.ByThreads(subscription.agentId, emptySet())
        } else {
            MessageSubscription.ByThreads(subscription.agentId, updatedThreadIds)
        }

        messagesByThreadsSubscriptions[subscription.agentId] = updatedSubscription

        return updatedSubscription
    }

    // Function to get all agents subscribed to a channel
    fun getSubscribedAgents(channel: MessageChannel): List<AgentId> {
        return messagesByChannelsSubscriptions
            .filter { (_, subscriptions) ->
                channel in subscriptions.channels
            }
            .map { (agentId, _) -> agentId }
            .toList()
    }

    // Function to get all agents subscribed to a thread
    fun getSubscribedAgents(threadId: MessageThreadId): List<AgentId> {
        return messagesByThreadsSubscriptions
            .filter { (_, subscriptions) ->
                threadId in subscriptions.threadIds
            }
            .map { (agentId, _) -> agentId }
            .toList()
    }
}
