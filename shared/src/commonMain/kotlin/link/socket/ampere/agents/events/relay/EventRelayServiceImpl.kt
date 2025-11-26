package link.socket.ampere.agents.events.relay

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Instant
import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.EventClassType
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Default implementation of [EventRelayService] that uses [EventSerialBus] for live events
 * and [EventRepository] for historical replay.
 *
 * The implementation bridges the callback-based EventBus to Flow-based streams for
 * reactive consumption.
 */
class EventRelayServiceImpl(
    private val eventSerialBus: EventSerialBus,
    private val eventRepository: EventRepository
) : EventRelayService {

    override fun subscribeToLiveEvents(filters: EventRelayFilters): Flow<Event> = callbackFlow {
        // If no filters, subscribe to all event types
        // Otherwise, subscribe to each filtered event type separately
        val eventTypes = filters.eventTypes ?: getAllKnownEventTypes()

        // Create subscriptions for each event type
        val subscriptions = eventTypes.map { eventType ->
            eventSerialBus.subscribe(
                agentId = "event-stream-${generateUUID()}",
                eventClassType = eventType,
                handler = EventHandler { event: Event, _: Subscription? ->
                    // Apply additional filtering beyond the event type
                    if (filters.matches(event)) {
                        trySend(event)
                    }
                }
            )
        }

        // Keep the flow alive until canceled
        awaitClose {
            // Unsubscribe from all event types when the flow is canceled
            eventTypes.forEach { eventType ->
                eventSerialBus.unsubscribe(eventType)
            }
        }
    }

    override suspend fun replayEvents(
        fromTime: Instant,
        toTime: Instant,
        filters: EventRelayFilters
    ): Result<Flow<Event>> {
        return eventRepository.getEventsBetween(fromTime, toTime).map { events ->
            flow {
                events.forEach { event ->
                    if (filters.matches(event)) {
                        emit(event)
                    }
                }
            }
        }
    }

    /**
     * Returns a set of all known event types in the system.
     * This is used when subscribing to all events (no filter).
     *
     * Note: This list should be kept in sync with all Event subclasses.
     * A more robust solution would use reflection or a registry pattern,
     * but for now we maintain this manually.
     */
    private fun getAllKnownEventTypes(): Set<EventClassType> {
        return setOf(
            Event.TaskCreated.EVENT_CLASS_TYPE,
            Event.QuestionRaised.EVENT_CLASS_TYPE,
            Event.CodeSubmitted.EVENT_CLASS_TYPE,
            // Add more event types as they are defined
            // MessageEvent types, MeetingEvent types, TicketEvent types, etc.
        )
    }
}
