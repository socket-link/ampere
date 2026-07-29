package link.socket.ampere.agents.events.bus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.ConsoleEventLogger
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.util.runBlockingCompat

/**
 * One handler listening on one [EventType], addressable by the [subscription] the bus
 * handed back to whoever registered it.
 *
 * Registrations are what make per-subscriber teardown possible. Two subscribers to the
 * same event type hold two registrations; releasing one leaves the other listening.
 */
internal class HandlerRegistration(
    val subscription: Subscription,
    val handler: EventHandler<Event, Subscription>,
)

internal typealias HandlerMap = MutableMap<EventType, List<HandlerRegistration>>
internal typealias SubscriptionMap = MutableMap<EventType, Subscription>

/**
 * EventSerialBus (ESB) is a thread-safe, Kotlin Multiplatform-compatible event bus.
 * Uses serialized event subscriptions to allow asynchronous two-way communication, which is then used
 * to enable publish-subscribe communication between Agents and Humans.
 *
 * Features:
 * - Thread-safe and Kotlin Multiplatform compatible
 * - Handlers are invoked asynchronously using the provided [CoroutineScope]
 * - Persistence is handled by higher-level APIs; EventBus only dispatches events to subscribers
 *
 * ### Choosing a subscribe/unsubscribe overload
 *
 * [subscribe] and [unsubscribe] are non-suspending, which they buy by taking the bus mutex
 * under [runBlockingCompat]. That blocks the calling thread, throws outright on JS/WasmJS,
 * and must never be called from a UI thread. Any caller that can suspend should prefer
 * [subscribeSuspending] / [unsubscribeSuspending], which take the same mutex without blocking.
 */
class EventSerialBus(
    private val scope: CoroutineScope,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    /** Map from EventClassType -> the handler registrations listening on it, in registration order */
    private val handlerMap: HandlerMap = mutableMapOf()

    /** Map from EventClassType -> a representative Subscription, kept for unsubscribe-by-type logging */
    private val subscriptionMap: SubscriptionMap = mutableMapOf()

    private val mutex = Mutex()

    /**
     * Publish an [event] to all subscribers of its exact KClass.
     * - Handlers are launched asynchronously on [scope].
     * - Any individual handler failures are swallowed to avoid impacting other subscribers.
     */
    suspend fun publish(event: Event) {
        // Collect all event types to dispatch to: own type + parent types for polymorphic delivery.
        val dispatchTypes = buildSet {
            add(event.eventType)
            addAll(event.parentEventTypes)
        }

        // Snapshot registrations under lock to maintain ordering and thread-safety.
        val registrations: List<HandlerRegistration> = mutex.withLock {
            dispatchTypes.flatMap { type -> handlerMap[type].orEmpty() }
        }

        if (registrations.isEmpty()) {
            return
        }

        logger.logPublish(event)

        for (registration in registrations) {
            scope.launch {
                try {
                    // Each handler sees its own Subscription, not whichever subscriber happened
                    // to register for this event type first.
                    registration.handler(event, registration.subscription)
                } catch (throwable: Throwable) {
                    // Swallow exceptions from handlers to avoid impacting other subscribers, but still log them.
                    logger.logError(
                        message = "Subscriber handler failure for ${event.eventType}(id=${event.eventId})",
                        throwable = throwable,
                    )
                }
            }
        }
    }

    /**
     * Publish an [event] from a synchronous call site without blocking the
     * caller. The event is still routed through [publish], so handler snapshot
     * and dispatch semantics stay centralized.
     */
    fun publishAsync(event: Event) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            publish(event)
        }
    }

    /**
     * Subscribe to events of [eventType]. Returns an [EventSubscription] that can be used to
     * [unsubscribe]. The [handler] runs asynchronously for each matching event.
     *
     * Blocks the calling thread while it takes the bus mutex. Prefer [subscribeSuspending] from
     * anywhere that can suspend.
     */
    fun subscribe(
        agentId: AgentId,
        eventType: EventType,
        handler: EventHandler<Event, Subscription>,
    ): Subscription {
        val registration = newRegistration(agentId, eventType, handler)

        runBlockingLock { register(eventType, registration) }

        logger.logSubscription(eventType, registration.subscription)

        return registration.subscription
    }

    /**
     * Suspending twin of [subscribe]: same registration semantics, no [runBlockingCompat].
     *
     * This is the overload to build Flows on — a `callbackFlow` or `flow` builder can call it
     * directly, and unlike [subscribe] it works on every target and never blocks a UI thread.
     */
    suspend fun subscribeSuspending(
        agentId: AgentId,
        eventType: EventType,
        handler: EventHandler<Event, Subscription>,
    ): Subscription {
        val registration = newRegistration(agentId, eventType, handler)

        mutex.withLock { register(eventType, registration) }

        logger.logSubscription(eventType, registration.subscription)

        return registration.subscription
    }

    /**
     * Remove **every** handler registered for [eventType], regardless of who registered it.
     *
     * This is a blunt instrument kept for compatibility: one subscriber calling it tears down
     * every other subscriber on that type. Prefer [unsubscribe] with the [Subscription] returned
     * by [subscribe] — that removes only your own handler.
     */
    fun unsubscribe(eventType: EventType) {
        runBlockingLock {
            val subscription = subscriptionMap[eventType] ?: return@runBlockingLock

            subscriptionMap.remove(eventType)
            handlerMap.remove(eventType)

            // Log unsubscription
            logger.logUnsubscription(eventType, subscription)
        }
    }

    /**
     * Remove only the handler registered under [subscription], leaving every other subscriber
     * on the same event type intact.
     *
     * Idempotent: unsubscribing an already-released subscription is a no-op.
     *
     * Matching is by object identity, so pass back the exact instance [subscribe] returned. A
     * serialization round-trip produces an equal-but-distinct [Subscription] that this will not
     * match — deliberately, because two subscribers with the same `agentId` and event type
     * produce equal `subscriptionId`s and only identity can tell them apart.
     *
     * Blocks the calling thread while it takes the bus mutex; prefer [unsubscribeSuspending].
     */
    fun unsubscribe(subscription: Subscription) {
        val releasedTypes = runBlockingLock { release(subscription) }

        releasedTypes.forEach { eventType -> logger.logUnsubscription(eventType, subscription) }
    }

    /** Suspending twin of [unsubscribe]: same removal semantics, no [runBlockingCompat]. */
    suspend fun unsubscribeSuspending(subscription: Subscription) {
        val releasedTypes = mutex.withLock { release(subscription) }

        releasedTypes.forEach { eventType -> logger.logUnsubscription(eventType, subscription) }
    }

    private fun newRegistration(
        agentId: AgentId,
        eventType: EventType,
        handler: EventHandler<Event, Subscription>,
    ): HandlerRegistration = HandlerRegistration(
        subscription = EventSubscription.ByEventClassType(
            agentIdOverride = agentId,
            eventTypes = setOf(eventType),
        ),
        handler = handler,
    )

    /** Must be called while holding [mutex]. */
    private fun register(eventType: EventType, registration: HandlerRegistration) {
        handlerMap[eventType] = handlerMap[eventType].orEmpty() + registration
        subscriptionMap.getOrPut(eventType) { registration.subscription }
    }

    /**
     * Must be called while holding [mutex]. Returns the event types [subscription] was
     * actually removed from, so the caller can log outside the lock.
     */
    private fun release(subscription: Subscription): List<EventType> {
        val releasedTypes = mutableListOf<EventType>()

        for ((eventType, registrations) in handlerMap.entries.toList()) {
            val remaining = registrations.filterNot { it.subscription === subscription }
            if (remaining.size == registrations.size) continue

            releasedTypes += eventType

            if (remaining.isEmpty()) {
                handlerMap.remove(eventType)
                subscriptionMap.remove(eventType)
            } else {
                handlerMap[eventType] = remaining
                // The representative subscription is the one we just dropped; promote a survivor.
                if (subscriptionMap[eventType] === subscription) {
                    subscriptionMap[eventType] = remaining.first().subscription
                }
            }
        }

        return releasedTypes
    }

    /** Helper to reuse the same locking pattern in non-suspending API without exposing Mutex */
    private inline fun <R> runBlockingLock(
        crossinline block: () -> R,
    ): R = runBlockingCompat {
        // Fast-path tryLock not used to keep logic simple and deterministic.
        mutex.withLock { block() }
    }
}

/**
 * Inline reified helper for ergonomic subscriptions.
 */
inline fun <reified E : Event, reified S : Subscription> EventSerialBus.subscribe(
    agentId: AgentId,
    eventType: EventType,
    noinline handler: suspend (E, S?) -> Unit,
): Subscription = subscribe(
    agentId = agentId,
    eventType = eventType,
    handler = EventHandler { event, subscription ->
        handler(event as E, subscription as S?)
    },
)
