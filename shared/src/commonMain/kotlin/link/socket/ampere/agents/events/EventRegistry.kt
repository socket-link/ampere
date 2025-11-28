package link.socket.ampere.agents.events

/**
 * Central registry of all event types in the system.
 *
 * This is the single source of truth for all event types. When adding a new event type:
 * 1. Add it to the [allEventTypes] list
 * 2. It will automatically be available in EventTypeParser and EnvironmentService
 *
 * Benefits:
 * - Single place to maintain event type list
 * - Prevents inconsistencies between different parts of the system
 * - Easy to see all available event types at a glance
 */
object EventRegistry {
    /**
     * All event types registered in the system.
     *
     * This list is used by:
     * - EventTypeParser for CLI filtering
     * - EnvironmentService.subscribeToAll() for subscribing to all events
     * - Any other component that needs to enumerate event types
     */
    val allEventTypes: List<EventClassType> = listOf(
        // Base Event types
        Event.TaskCreated.EVENT_CLASS_TYPE,
        Event.QuestionRaised.EVENT_CLASS_TYPE,
        Event.CodeSubmitted.EVENT_CLASS_TYPE,

        // MeetingEvent types
        MeetingEvent.MeetingScheduled.EVENT_CLASS_TYPE,
        MeetingEvent.MeetingStarted.EVENT_CLASS_TYPE,
        MeetingEvent.AgendaItemStarted.EVENT_CLASS_TYPE,
        MeetingEvent.AgendaItemCompleted.EVENT_CLASS_TYPE,
        MeetingEvent.MeetingCompleted.EVENT_CLASS_TYPE,
        MeetingEvent.MeetingCanceled.EVENT_CLASS_TYPE,

        // TicketEvent types
        TicketEvent.TicketCreated.EVENT_CLASS_TYPE,
        TicketEvent.TicketStatusChanged.EVENT_CLASS_TYPE,
        TicketEvent.TicketAssigned.EVENT_CLASS_TYPE,
        TicketEvent.TicketBlocked.EVENT_CLASS_TYPE,
        TicketEvent.TicketCompleted.EVENT_CLASS_TYPE,
        TicketEvent.TicketMeetingScheduled.EVENT_CLASS_TYPE,

        // MessageEvent types
        MessageEvent.ThreadCreated.EVENT_CLASS_TYPE,
        MessageEvent.MessagePosted.EVENT_CLASS_TYPE,
        MessageEvent.ThreadStatusChanged.EVENT_CLASS_TYPE,
        MessageEvent.EscalationRequested.EVENT_CLASS_TYPE,

        // NotificationEvent types
        NotificationEvent.ToAgent.EVENT_CLASS_TYPE,
        NotificationEvent.ToHuman.EVENT_CLASS_TYPE,
    )

    /**
     * Map of event type names (lowercase) to their EventClassType.
     * Used for parsing command-line arguments.
     */
    val eventTypeByName: Map<String, EventClassType> by lazy {
        allEventTypes.associateBy { it.second.lowercase() }
    }

    /**
     * Get all event type names (for display purposes).
     */
    fun getAllEventTypeNames(): Set<String> = eventTypeByName.keys

    /**
     * Parse an event type name to its EventClassType.
     */
    fun parseEventType(typeName: String): EventClassType? =
        eventTypeByName[typeName.lowercase()]

    /**
     * Parse multiple event type names to a Set of EventClassTypes.
     */
    fun parseEventTypes(typeNames: List<String>): Set<EventClassType> =
        typeNames.mapNotNull { parseEventType(it) }.toSet()
}
