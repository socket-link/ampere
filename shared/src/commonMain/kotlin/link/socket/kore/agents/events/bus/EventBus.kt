package link.socket.kore.agents.events.bus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.socket.kore.agents.core.AgentId
import link.socket.kore.agents.events.Event
import link.socket.kore.agents.events.EventClassType
import link.socket.kore.agents.events.api.EventHandler
import link.socket.kore.agents.events.subscription.EventSubscription
import link.socket.kore.agents.events.subscription.Subscription
import link.socket.kore.agents.events.utils.ConsoleEventLogger
import link.socket.kore.agents.events.utils.EventLogger

typealias HandlerMap = MutableMap<EventClassType, List<EventHandler<Event, Subscription>>>
typealias SubscriptionMap = MutableMap<EventClassType, Subscription>

/**
 * Type-safe event bus for publish-subscribe communication between agents with optional persistence.
 *
 * - Thread-safe and Kotlin Multiplatform compatible
 * - Handlers are invoked asynchronously using the provided [CoroutineScope]
 * - Persistence is handled by higher-level APIs; EventBus only dispatches events to subscribers
 */
class EventBus(
    private val scope: CoroutineScope,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    // Map event from EventClassType -> (subscriptionId, eventHandler)
    private val handlerMap: HandlerMap = mutableMapOf()

    // Map from subscriptionId -> EventClassType (to efficiently locate the handler on unsubscribe)
    private val subscriptionMap: SubscriptionMap = mutableMapOf()

    private val mutex = Mutex()

    /**
     * Publish an [event] to all subscribers of its exact KClass.
     * - Handlers are launched asynchronously on [scope].
     * - Any individual handler failures are swallowed to avoid impacting other subscribers.
     */
    suspend fun publish(event: Event) {
        // Snapshot handlers under lock to maintain ordering and thread-safety
        val handlers: List<EventHandler<Event, Subscription>> = mutex.withLock {
            handlerMap[event.eventClassType].orEmpty()
        }

        if (handlers.isEmpty()) {
            return
        }

        logger.logPublish(event)

        for (handler in handlers) {
            scope.launch {
                try {
                    val subscription = subscriptionMap[event.eventClassType]
                    handler(event, subscription)
                } catch (throwable: Throwable) {
                    // Swallow exceptions from handlers to avoid impacting other subscribers, but still log them.
                    logger.logError(
                        message = "Subscriber handler failure for ${event.eventClassType}(id=${event.eventId})",
                        throwable = throwable,
                    )
                }
            }
        }
    }

    /**
     * Subscribe to events of [eventClassType]. Returns a [EventSubscription] that can be used to
     * [unsubscribe]. The [handler] runs asynchronously for each matching event.
     */
    @Suppress("UNCHECKED_CAST")
    fun subscribe(
        agentId: AgentId,
        eventClassType: EventClassType,
        handler: EventHandler<Event, Subscription>,
    ): Subscription {
        val subscription = EventSubscription.ByEventClassType(
            agentIdOverride = agentId,
            eventClassTypes = setOf(eventClassType),
        )

        val eventHandler: EventHandler<Event, Subscription> = EventHandler { event, subscription ->
            handler(event, subscription)
        }

        // Register handler under lock
        runBlockingLock {
            val existing = handlerMap[eventClassType]
            val updated = if (existing == null) listOf(eventHandler) else existing + eventHandler
            handlerMap[eventClassType] = updated
            subscriptionMap.getOrPut(eventClassType) { subscription }
        }

        // Log subscription
        logger.logSubscription(eventClassType, subscription)

        return subscription
    }

    fun unsubscribe(eventClassType: EventClassType) {
        runBlockingLock {
            // TODO: Potentially cancel subscription before removing
            val subscription = subscriptionMap[eventClassType] ?: return@runBlockingLock

            subscriptionMap.remove(eventClassType)
            handlerMap.remove(eventClassType)

            // Log unsubscription
            logger.logUnsubscription(eventClassType, subscription)
        }
    }

    // Helper to reuse the same locking pattern in non-suspending API without exposing Mutex.
    private inline fun <R> runBlockingLock(
        crossinline block: () -> R,
    ): R = runBlocking {
        // Fast-path tryLock not used to keep logic simple and deterministic.
        mutex.withLock { block() }
    }
}

/**
 * Inline reified helper for ergonomic subscriptions.
 */
inline fun <reified E : Event, reified S : Subscription> EventBus.subscribe(
    agentId: AgentId,
    eventClassType: EventClassType,
    noinline handler: suspend (E, S?) -> Unit,
): Subscription = subscribe(
    agentId = agentId,
    eventClassType = eventClassType,
    handler = EventHandler { event, subscription ->
        handler(event as E, subscription as S?)
    },
)
