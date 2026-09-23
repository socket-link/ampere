package link.socket.ampere.agents.events

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.subscription.EventSubscription

/**
 * Fans task, question and code events out to the agents subscribed to their type as
 * [NotificationEvent.ToAgent]s.
 *
 * Every notification is published through the door ([AgentEventApi.publish], F1) with
 * `causedBy` set to the event it notifies about (F2). The handlers here return `Unit`, so a
 * notification that fails to persist is logged by the door and not dispatched; there is no
 * caller to return it to.
 */
class EventRouter(
    private val eventApi: AgentEventApi,
) {
    private val eventsByEventClassTypeSubscriptions = mutableMapOf<AgentId, EventSubscription.ByEventClassType>()

    fun startRouting() {
        getSubscribedAgentsFor(Event.TaskCreated.EVENT_TYPE).forEach { agentId ->
            eventApi.onTaskCreated { event, subscription ->
                NotificationEvent.ToAgent(
                    agentId = agentId,
                    event = event,
                    subscription = subscription,
                ).let { notificationEvent -> eventApi.publish(notificationEvent, causedBy = event.eventId) }
            }
        }

        getSubscribedAgentsFor(Event.QuestionRaised.EVENT_TYPE).forEach { agentId ->
            eventApi.onQuestionRaised { event, subscription ->
                NotificationEvent.ToAgent(
                    agentId = agentId,
                    event = event,
                    subscription = subscription,
                ).let { notificationEvent -> eventApi.publish(notificationEvent, causedBy = event.eventId) }
            }
        }

        getSubscribedAgentsFor(Event.CodeSubmitted.EVENT_TYPE).forEach { agentId ->
            eventApi.onCodeSubmitted { event, subscription ->
                NotificationEvent.ToAgent(
                    agentId = agentId,
                    event = event,
                    subscription = subscription,
                ).let { notificationEvent -> eventApi.publish(notificationEvent, causedBy = event.eventId) }
            }
        }
    }

    fun subscribeToEventClassType(
        agentId: AgentId,
        eventType: EventType,
    ): EventSubscription.ByEventClassType {
        val updatedSubscription =
            eventsByEventClassTypeSubscriptions[agentId]
                ?.let { existingSubscription ->
                    val newEventClassTypes = existingSubscription.eventTypes.plus(eventType)
                    EventSubscription.ByEventClassType(
                        agentIdOverride = agentId,
                        eventTypes = newEventClassTypes,
                    )
                } ?: EventSubscription.ByEventClassType(
                agentIdOverride = agentId,
                eventTypes = setOf(eventType),
            )

        eventsByEventClassTypeSubscriptions[agentId] = updatedSubscription

        return updatedSubscription
    }

    fun EventSubscription.ByEventClassType.unsubscribeFromEventClassType(
        eventType: EventType,
    ): EventSubscription.ByEventClassType {
        val updatedSubscription = copy(
            eventTypes = eventTypes - eventType,
        )

        eventsByEventClassTypeSubscriptions[agentId] = updatedSubscription
        return updatedSubscription
    }

    // ** Function to get all agents that are subscribed to an event type. */
    fun getSubscribedAgentsFor(
        eventType: EventType,
    ): List<AgentId> =
        eventsByEventClassTypeSubscriptions
            .filterValues { subscriptions ->
                eventType in subscriptions.eventTypes
            }
            .map { (agentId, _) -> agentId }
            .toList()
}
