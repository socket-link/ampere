package link.socket.ampere.api.service

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.relay.EventRelayFilters

/**
 * SDK service for event stream observation and querying.
 *
 * This is the "glass brain" — AMPERE's core differentiator. Every cognitive
 * state change, coordination event, and decision flows through here.
 *
 * Maps to CLI command: `watch`
 */
interface EventService {

    /**
     * Observe the live event stream.
     *
     * ```
     * ampere.events.observe()
     *     .filter { it is TicketEvent }
     *     .collect { event -> handleEvent(event) }
     * ```
     *
     * @param filters Optional filters to limit which events are emitted
     * @return Hot flow of events matching the filter criteria
     */
    fun observe(filters: EventRelayFilters = EventRelayFilters()): Flow<Event>

    /**
     * Query historical events from persistent storage.
     *
     * @param fromTime Start of time range (inclusive)
     * @param toTime End of time range (inclusive)
     * @param sourceIds Optional set of agent IDs to filter by
     * @return List of matching events in chronological order
     */
    suspend fun query(
        fromTime: Instant,
        toTime: Instant,
        sourceIds: Set<String>? = null,
    ): Result<List<Event>>

    /**
     * Replay a time range of events as a flow (useful for debugging).
     *
     * @param from Start of time range (inclusive)
     * @param to End of time range (inclusive)
     * @param filters Optional filters to limit which events are replayed
     * @return Flow of historical events in chronological order
     */
    fun replay(
        from: Instant,
        to: Instant,
        filters: EventRelayFilters = EventRelayFilters(),
    ): Flow<Event>
}
