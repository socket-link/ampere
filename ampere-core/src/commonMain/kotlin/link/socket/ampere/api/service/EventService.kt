package link.socket.ampere.api.service

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.relay.EventRelayFilters

/**
 * SDK service for event stream observation and querying.
 *
 * This is the "glass brain" — AMPERE's core differentiator. Every cognitive
 * state change, every coordination event, every decision flows through here.
 *
 * Maps to CLI command: `watch`
 *
 * ```
 * ampere.events.observe()
 *     .filter { it is TicketEvent }
 *     .collect { event ->
 *         when (event) {
 *             is TicketEvent.TicketCreated -> handleCreated(event)
 *             is TicketEvent.TicketAssigned -> handleAssigned(event)
 *         }
 *     }
 * ```
 */
interface EventService {

    /**
     * Observe the live event stream.
     *
     * The returned [Flow] is hot and will continue emitting events until cancelled.
     * Events are filtered according to the provided [filters].
     *
     * ```
     * // All events:
     * ampere.events.observe().collect { event -> println(event) }
     *
     * // Filtered by source:
     * ampere.events.observe(EventRelayFilters.forSource(EventSource.Agent("pm")))
     *     .collect { event -> println(event) }
     * ```
     *
     * @param filters Optional filters to limit which events are emitted
     * @return Hot flow of events matching the filter criteria
     */
    fun observe(filters: EventRelayFilters = EventRelayFilters()): Flow<Event>

    /**
     * Query historical events from persistent storage.
     *
     * ```
     * val recentEvents = ampere.events.query(
     *     fromTime = Clock.System.now() - 1.hours,
     *     toTime = Clock.System.now(),
     * )
     * ```
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
     * ```
     * ampere.events.replay(startTime, endTime).collect { event ->
     *     println("${event.timestamp}: ${event.eventType}")
     * }
     * ```
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
