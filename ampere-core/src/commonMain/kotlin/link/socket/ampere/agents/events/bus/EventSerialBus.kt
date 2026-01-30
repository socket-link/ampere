package link.socket.ampere.agents.events.bus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

typealias HandlerMap = MutableMap<EventType, List<EventHandler<Event, Subscription>>>
typealias SubscriptionMap = MutableMap<EventType, Subscription>

/**
 * EventSerialBus (ESB) is a thread-safe, Kotlin Multiplatform-compatible event bus.
 * Uses serialized event subscriptions to allow asynchronous two-way communication, which is then used
 * to enable publish-subscribe communication between Agents and Humans.
 *
 * Features:
 * - Thread-safe and Kotlin Multiplatform compatible
 * - Handlers are invoked asynchronously using the provided [CoroutineScope]
 * - Persistence is handled by higher-level APIs; EventBus only dispatches events to subscribers
 */
class EventSerialBus(
    private val scope: CoroutineScope,
    private val logger: EventLogger = ConsoleEventLogger(),
) {
    /** Map event from EventClassType -> (subscriptionId, eventHandler) */
    private val handlerMap: HandlerMap = mutableMapOf()

    /** Map from subscriptionId -> EventClassType (to efficiently locate the handler on unsubscribe) */
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
            handlerMap[event.eventType].orEmpty()
        }

        if (handlers.isEmpty()) {
            return
        }

        logger.logPublish(event)

        for (handler in handlers) {
            scope.launch {
                try {
                    val subscription = subscriptionMap[event.eventType]
                    handler(event, subscription)
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
     * Subscribe to events of [eventType]. Returns an [EventSubscription] that can be used to
     * [unsubscribe]. The [handler] runs asynchronously for each matching event.
     */
    @Suppress("UNCHECKED_CAST")
    fun subscribe(
        agentId: AgentId,
        eventType: EventType,
        handler: EventHandler<Event, Subscription>,
    ): Subscription {
        val subscription = EventSubscription.ByEventClassType(
            agentIdOverride = agentId,
            eventTypes = setOf(eventType),
        )

        val eventHandler: EventHandler<Event, Subscription> = EventHandler { event, subscription ->
            handler(event, subscription)
        }

        // Register handler under lock
        runBlockingLock {
            val existing = handlerMap[eventType]

            val updated = if (existing == null) {
                listOf(eventHandler)
            } else {
                existing + eventHandler
            }

            handlerMap[eventType] = updated
            subscriptionMap.getOrPut(eventType) { subscription }
        }

        // Log subscription
        logger.logSubscription(eventType, subscription)

        return subscription
    }

    fun unsubscribe(eventType: EventType) {
        runBlockingLock {
            // TODO: Potentially cancel subscription before removing
            val subscription = subscriptionMap[eventType] ?: return@runBlockingLock

            subscriptionMap.remove(eventType)
            handlerMap.remove(eventType)

            // Log unsubscription
            logger.logUnsubscription(eventType, subscription)
        }
    }

    /** Helper to reuse the same locking pattern in non-suspending API without exposing Mutex */
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
