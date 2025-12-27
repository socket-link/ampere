package link.socket.ampere.agents.events.relay

import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventType

/**
 * Filter criteria for event streams.
 *
 * All filter fields are optional. When a field is null, it means "no filter on this dimension".
 * When multiple fields are non-null, they are combined with AND logic.
 *
 * Within each Set-based field (eventTypes, sourceIds), items are combined with OR logic.
 *
 * Example:
 * ```
 * EventFilters(
 *     eventTypes = setOf(EventClassType.from<Event.TaskCreated>()),
 *     sourceIds = setOf("agent-1", "agent-2")
 * )
 * ```
 * This will match events that are TaskCreated events AND come from either agent-1 OR agent-2.
 *
 * @property eventTypes Set of event class types to include. Null means all event types.
 * @property eventSources Set of source IDs (agent IDs or human IDs) to include. Null means all sources.
 * @property urgencies Set of urgency levels to include. Null means all urgency levels.
 * @property eventIds Set of specific event IDs to include. Null means all events.
 */
data class EventRelayFilters(
    val eventTypes: Set<EventType>? = null,
    val eventSources: Set<EventSource>? = null,
    val urgencies: Set<Urgency>? = null,
    val eventIds: Set<EventId>? = null,
) {
    /**
     * Returns true if all filters are null (i.e., no filtering).
     */
    fun isEmpty(): Boolean =
        eventTypes == null &&
            eventSources == null &&
            urgencies == null &&
            eventIds == null

    /**
     * Returns true if the given event matches these filters.
     *
     * @param event The event to check
     * @return true if the event passes all non-null filters
     */
    fun matches(event: Event): Boolean {
        if (eventTypes != null && event.eventType !in eventTypes) return false

        if (eventSources != null && event.eventSource !in eventSources) return false

        if (urgencies != null && event.urgency !in urgencies) return false

        if (eventIds != null && event.eventId !in eventIds) return false

        return true
    }

    companion object Companion {
        /** Creates a filter that matches all events (no filtering) */
        val NONE = EventRelayFilters()

        /** Creates a filter for a single event type */
        fun forEventType(eventType: EventType): EventRelayFilters =
            EventRelayFilters(eventTypes = setOf(eventType))

        /** Creates a filter for multiple event types */
        fun forEventTypes(eventTypes: List<EventType>): EventRelayFilters =
            EventRelayFilters(eventTypes = eventTypes.toSet())

        fun forSources(sources: List<EventSource>): EventRelayFilters =
            EventRelayFilters(eventSources = sources.toSet())

        /** Creates a filter for a single source ID */
        fun forSource(source: EventSource): EventRelayFilters =
            EventRelayFilters(eventSources = setOf(source))

        /** Creates a filter for a single urgency level */
        fun forUrgency(urgency: Urgency): EventRelayFilters =
            EventRelayFilters(urgencies = setOf(urgency))
    }
}
