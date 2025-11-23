package link.socket.ampere.agents.events

import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventBus
import link.socket.ampere.agents.events.subscription.EventSubscription

class EventRouter(
    private val eventApi: AgentEventApi,
    private val eventBus: EventBus,
) {
    private val eventsByEventClassTypeSubscriptions = mutableMapOf<AgentId, EventSubscription.ByEventClassType>()

    fun startRouting() {
        getSubscribedAgentsFor(Event.TaskCreated.EVENT_CLASS_TYPE).forEach { agentId ->
            eventApi.onTaskCreated { event, subscription ->
                NotificationEvent.ToAgent(
                    agentId = agentId,
                    event = event,
                    eventSubscription = subscription,
                ).let { notificationEvent -> eventBus.publish(notificationEvent) }
            }
        }

        getSubscribedAgentsFor(Event.QuestionRaised.EVENT_CLASS_TYPE).forEach { agentId ->
            eventApi.onQuestionRaised { event, subscription ->
                NotificationEvent.ToAgent(
                    agentId = agentId,
                    event = event,
                    eventSubscription = subscription,
                ).let { notificationEvent -> eventBus.publish(notificationEvent) }
            }
        }

        getSubscribedAgentsFor(Event.CodeSubmitted.EVENT_CLASS_TYPE).forEach { agentId ->
            eventApi.onCodeSubmitted { event, subscription ->
                NotificationEvent.ToAgent(
                    agentId = agentId,
                    event = event,
                    eventSubscription = subscription,
                ).let { notificationEvent -> eventBus.publish(notificationEvent) }
            }
        }
    }

    fun subscribeToEventClassType(
        agentId: AgentId,
        eventClassType: EventClassType,
    ): EventSubscription.ByEventClassType {
        val updatedSubscription =
            eventsByEventClassTypeSubscriptions[agentId]
                ?.let { existingSubscription ->
                    val newEventClassTypes = existingSubscription.eventClassTypes.plus(eventClassType)
                    EventSubscription.ByEventClassType(
                        agentIdOverride = agentId,
                        eventClassTypes = newEventClassTypes,
                    )
                } ?: EventSubscription.ByEventClassType(
                agentIdOverride = agentId,
                eventClassTypes = setOf(eventClassType),
            )

        eventsByEventClassTypeSubscriptions[agentId] = updatedSubscription

        return updatedSubscription
    }

    fun EventSubscription.ByEventClassType.unsubscribeFromEventClassType(
        eventClassType: EventClassType,
    ): EventSubscription.ByEventClassType {
        val updatedSubscription = copy(
            eventClassTypes = eventClassTypes - eventClassType,
        )

        eventsByEventClassTypeSubscriptions[agentId] = updatedSubscription
        return updatedSubscription
    }

    // ** Function to get all agents that are subscribed to an event type. */
    fun getSubscribedAgentsFor(
        eventClassType: EventClassType,
    ): List<AgentId> =
        eventsByEventClassTypeSubscriptions
            .filterValues { subscriptions ->
                eventClassType in subscriptions.eventClassTypes
            }
            .map { (agentId, _) -> agentId }
            .toList()
}
