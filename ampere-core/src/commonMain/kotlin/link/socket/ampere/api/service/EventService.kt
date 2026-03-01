package link.socket.ampere.api.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.FileSystemEvent
import link.socket.ampere.agents.domain.event.GitEvent
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProductEvent
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.event.SparkEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.event.ToolEvent
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
@link.socket.ampere.api.AmpereStableApi
interface EventService {

    /**
     * Retrieve a specific event by ID.
     *
     * ```
     * val event = ampere.events.get("event-123").getOrNull()
     * event?.let { println("${it.eventType}: ${it.getSummary()}") }
     * ```
     *
     * @param eventId The ID of the event to retrieve
     * @return The event, or null if not found
     */
    suspend fun get(eventId: String): Result<Event?>

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
     * Observe the live event stream using coarse-grained event categories.
     *
     * This is an additive convenience API for external consumers that want
     * stable subscription groups like telemetry without constructing explicit
     * [EventRelayFilters] instances.
     */
    fun observe(filter: EventStreamFilter): Flow<Event> = when (filter) {
        EventStreamFilter.ALL -> observe()
        else -> observe().filter(filter::matches)
    }

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

    /**
     * Replay historical events using coarse-grained event categories.
     */
    fun replay(
        from: Instant,
        to: Instant,
        filter: EventStreamFilter,
    ): Flow<Event> = when (filter) {
        EventStreamFilter.ALL -> replay(from, to)
        else -> replay(from, to).filter(filter::matches)
    }
}

@link.socket.ampere.api.AmpereStableApi
fun EventService.routingEvents(
    filters: EventRelayFilters = EventRelayFilters(),
): Flow<RoutingEvent> = observe(filters).filterIsInstance<RoutingEvent>()

@link.socket.ampere.api.AmpereStableApi
fun EventService.completionEvents(
    filters: EventRelayFilters = EventRelayFilters(),
): Flow<ProviderCallCompletedEvent> = observe(filters).filterIsInstance<ProviderCallCompletedEvent>()

@link.socket.ampere.api.AmpereStableApi
enum class EventStreamFilter {
    ALL,
    TELEMETRY,
    LIFECYCLE,
    COORDINATION,
    COGNITIVE,
    ;

    fun matches(event: Event): Boolean = when (this) {
        ALL -> true
        TELEMETRY -> event is ProviderCallStartedEvent || event is ProviderCallCompletedEvent
        LIFECYCLE -> when (event) {
            is Event.TaskCreated,
            is TaskEvent,
            is TicketEvent,
            is MeetingEvent,
            is FileSystemEvent,
            is GitEvent,
            is ToolEvent.ToolExecutionStarted,
            is ToolEvent.ToolExecutionCompleted,
            -> true
            else -> false
        }
        COORDINATION -> when (event) {
            is MessageEvent,
            is NotificationEvent<*>,
            is HumanInteractionEvent,
            -> true
            else -> false
        }
        COGNITIVE -> when (event) {
            is Event.QuestionRaised,
            is Event.CodeSubmitted,
            is MemoryEvent,
            is PlanEvent,
            is ProductEvent,
            is RoutingEvent,
            is SparkEvent,
            -> true
            else -> false
        }
    }
}
