package link.socket.ampere.agents.events.relay

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.bus.EventSerialBus

/**
 * Service for subscribing to and/or replaying events from the ESB.
 *
 * Provides two modes of event access:
 * 1. **Live streaming**: Subscribe to events as they occur in real-time via [EventSerialBus]
 * 2. **Historical replay**: Query and replay events from persistent storage of [EventRepository]
 */
interface EventRelayService {

    /**
     * Subscribe to a real-time event stream from EventBus.
     *
     * The returned Flow is hot and will continue emitting events until cancelled.
     * Events are filtered according to the provided [filters].
     *
     * @param filters Optional filters to limit which events are emitted
     * @return Flow of events matching the filter criteria
     */
    fun subscribeToLiveEvents(
        filters: EventRelayFilters = EventRelayFilters(),
    ): Flow<Event>

    /**
     * Replay historical events from persistent storage.
     * Useful for time-travel debugging and understanding past system state.
     *
     * Events are returned in chronological order (oldest first) within the specified
     * time range, filtered according to the provided [filters].
     *
     * @param fromTime Start of time range (inclusive)
     * @param toTime End of time range (inclusive)
     * @param filters Optional filters to limit which events are returned
     * @return Result containing a Flow of historical events, or an error if the query fails
     */
    suspend fun replayEvents(
        fromTime: Instant,
        toTime: Instant,
        filters: EventRelayFilters = EventRelayFilters(),
    ): Result<Flow<Event>>
}
