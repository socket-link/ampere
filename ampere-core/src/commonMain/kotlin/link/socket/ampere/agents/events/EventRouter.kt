package link.socket.ampere.agents.events

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.subscription.Subscription

/**
 * Fans task, question and code events out to the agents registered for their type as
 * [NotificationEvent.ToAgent]s.
 *
 * ### Registration is independent of start order
 *
 * [startRouting] registers exactly one bus handler per [ROUTABLE_EVENT_TYPES] entry, and each
 * handler reads the registry at *dispatch* time. So [subscribeToEventClassType] works before
 * or after [startRouting], which matters because the only thing that calls [startRouting] is
 * `EnvironmentOrchestrator.start()` — long before a consumer has an agent to register
 * (AMPR-404). The previous shape iterated the registry inside [startRouting] and registered a
 * handler per agent found there, so the registry was always empty when it was read and no
 * agent ever received a notification.
 *
 * One handler per type rather than one per agent also keeps [startRouting] idempotent:
 * `NotificationEvent.ToAgent.eventId` is derived from the routed event's id and the target
 * agent, so two handlers for the same type would try to persist the same row twice and the
 * second publish would fail on the primary key.
 *
 * Register through `EnvironmentService.routeEventsToAgent(agentId, eventType)`, which is the
 * public path onto this router.
 *
 * ### What a notification carries
 *
 * Every notification is published through the door ([AgentEventApi.publish], F1) with
 * `causedBy` set to the event it notifies about (F2), and carries the recipient's own
 * [EventSubscription.ByEventClassType] — the registration that earned it the notification,
 * not the router's internal bus handle.
 *
 * It does **not** carry a `runId`: a bus handler is given the [Event], and the run id lives on
 * the [EventEnvelope] beside it, so the router has nothing to propagate (F4). A notification is
 * therefore reachable from the event it was `causedBy`, not from a run-id query. Closing that
 * gap means handing subscribers the envelope, which is out of scope here.
 *
 * The handlers return `Unit`, so a notification that fails to persist is logged by the door and
 * not dispatched; there is no caller to return it to.
 */
@OptIn(ExperimentalAtomicApi::class)
class EventRouter(
    private val eventApi: AgentEventApi,
) {
    /**
     * Who wants what, as an immutable map swapped under compare-and-set.
     *
     * Registration happens on whatever thread owns the consumer; dispatch happens on the bus's
     * scope. A plain `mutableMapOf` read from a bus handler while a consumer registers is a
     * data race, and on Kotlin/Native a `HashMap` iterated across a mutation throws outright.
     */
    private val subscriptionsByAgent =
        AtomicReference<Map<AgentId, EventSubscription.ByEventClassType>>(emptyMap())

    /**
     * The bus handles [startRouting] holds, or `null` when this router is not routing.
     *
     * Both facts live in one cell so `start` and `stop` are a single compare-and-set each and
     * cannot interleave into a state where handlers are registered but unreachable.
     */
    private val busSubscriptions = AtomicReference<List<Subscription>?>(null)

    /** True while this router is listening on the bus. */
    val isRouting: Boolean
        get() = busSubscriptions.load() != null

    /**
     * Begin fanning [ROUTABLE_EVENT_TYPES] out to registered agents.
     *
     * Idempotent: a second call while already routing is a no-op rather than a duplicate
     * registration.
     */
    fun startRouting() {
        if (busSubscriptions.load() != null) return

        val registered = listOf(
            eventApi.onTaskCreated { event, _ -> notifySubscribers(event) },
            eventApi.onQuestionRaised { event, _ -> notifySubscribers(event) },
            eventApi.onCodeSubmitted { event, _ -> notifySubscribers(event) },
        )

        if (busSubscriptions.compareAndSet(null, registered)) return

        // Another caller started routing between the read above and the swap. Release the
        // handlers this call registered so the winner's are the only ones listening — by
        // handle, so the winner's registrations on the same types survive.
        registered.forEach { subscription -> eventApi.eventSerialBus.unsubscribe(subscription) }
    }

    /**
     * Stop fanning events out, releasing the bus handles [startRouting] took.
     *
     * Registrations survive, so [startRouting] resumes routing to the same agents. Idempotent.
     */
    fun stopRouting() {
        val held = busSubscriptions.exchange(null) ?: return

        held.forEach { subscription -> eventApi.eventSerialBus.unsubscribe(subscription) }
    }

    /**
     * Register [agentId] for [eventType], returning its merged subscription.
     *
     * Additive: an agent already registered for other types keeps them. Takes effect for the
     * next matching event whether or not [startRouting] has been called yet.
     */
    fun subscribeToEventClassType(
        agentId: AgentId,
        eventType: EventType,
    ): EventSubscription.ByEventClassType {
        while (true) {
            val current = subscriptionsByAgent.load()
            val updated = EventSubscription.ByEventClassType(
                agentIdOverride = agentId,
                eventTypes = current[agentId]?.eventTypes.orEmpty() + eventType,
            )

            if (subscriptionsByAgent.compareAndSet(current, current + (agentId to updated))) {
                return updated
            }
        }
    }

    /**
     * Drop [agentId]'s registration for [eventType], returning what it is left registered for.
     *
     * An agent whose last type is removed leaves the registry entirely rather than staying in
     * it with an empty type set.
     */
    fun unsubscribeFromEventClassType(
        agentId: AgentId,
        eventType: EventType,
    ): EventSubscription.ByEventClassType {
        while (true) {
            val current = subscriptionsByAgent.load()
            val remaining = current[agentId]?.eventTypes.orEmpty() - eventType
            val updated = EventSubscription.ByEventClassType(
                agentIdOverride = agentId,
                eventTypes = remaining,
            )
            val next = if (remaining.isEmpty()) {
                current - agentId
            } else {
                current + (agentId to updated)
            }

            if (subscriptionsByAgent.compareAndSet(current, next)) {
                return updated
            }
        }
    }

    /** Every agent registered for [eventType], in registration order. */
    fun getSubscribedAgentsFor(
        eventType: EventType,
    ): List<AgentId> =
        subscriptionsByAgent
            .load()
            .filterValues { subscription -> eventType in subscription.eventTypes }
            .keys
            .toList()

    /**
     * Publish one [NotificationEvent.ToAgent] per agent registered for [event]'s type.
     *
     * Reads the registry here, not at [startRouting] time, which is what makes registration
     * order irrelevant.
     */
    private suspend fun notifySubscribers(event: Event) {
        val recipients = subscriptionsByAgent
            .load()
            .filterValues { subscription -> event.eventType in subscription.eventTypes }

        recipients.forEach { (agentId, subscription) ->
            eventApi.publish(
                event = NotificationEvent.ToAgent(
                    agentId = agentId,
                    event = event,
                    subscription = subscription,
                ),
                causedBy = event.eventId,
            )
        }
    }

    companion object {
        /**
         * The event types this router fans out.
         *
         * Adding one means adding a typed `on…` subscription in [startRouting] beside it; the
         * set exists so a caller can ask what is routable without reading that list.
         */
        val ROUTABLE_EVENT_TYPES: Set<EventType> = setOf(
            Event.TaskCreated.EVENT_TYPE,
            Event.QuestionRaised.EVENT_TYPE,
            Event.CodeSubmitted.EVENT_TYPE,
        )
    }
}
