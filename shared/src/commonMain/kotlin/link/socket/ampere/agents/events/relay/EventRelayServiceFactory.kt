package link.socket.ampere.agents.events.relay

import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.bus.EventSerialBus

/**
 * Factory for creating [EventRelayService] instances.
 *
 * Provides a centralized place to construct the service with all required dependencies.
 */
object EventRelayServiceFactory {

    /**
     * Creates a new [EventRelayService] instance.
     *
     * @param eventSerialBus EventBus for subscribing to live events
     * @param eventRepository EventRepository for accessing historical events
     * @return A new EventStreamService instance
     */
    fun create(
        eventSerialBus: EventSerialBus,
        eventRepository: EventRepository
    ): EventRelayService {
        return EventRelayServiceImpl(
            eventSerialBus = eventSerialBus,
            eventRepository = eventRepository
        )
    }
}
