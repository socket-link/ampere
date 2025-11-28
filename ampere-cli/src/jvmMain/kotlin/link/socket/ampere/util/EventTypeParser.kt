package link.socket.ampere.util

import link.socket.ampere.agents.events.EventClassType
import link.socket.ampere.agents.events.EventRegistry

/**
 * Utility for parsing event type names from command-line strings to EventClassType.
 *
 * This enables users to filter events by typing event names like "TaskCreated" or "MeetingScheduled".
 *
 * Delegates to EventRegistry for the single source of truth on event types.
 */
object EventTypeParser {

    /**
     * Parse a single event type name to its EventClassType.
     *
     * @param typeName The event type name (case-insensitive)
     * @return The EventClassType, or null if not found
     */
    fun parse(typeName: String): EventClassType? {
        return EventRegistry.parseEventType(typeName)
    }

    /**
     * Parse multiple event type names to a Set of EventClassTypes.
     *
     * @param typeNames List of event type names
     * @return Set of EventClassTypes (skips invalid names)
     */
    fun parseMultiple(typeNames: List<String>): Set<EventClassType> {
        return EventRegistry.parseEventTypes(typeNames)
    }

    /**
     * Get all available event type names.
     *
     * @return Set of all recognized event type names
     */
    fun getAllEventTypeNames(): Set<String> {
        return EventRegistry.getAllEventTypeNames()
    }

    /**
     * Get all EventClassTypes in the system.
     *
     * @return Set of all EventClassTypes
     */
    fun getAllEventTypes(): Set<EventClassType> {
        return EventRegistry.allEventTypes.toSet()
    }
}
