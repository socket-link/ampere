package link.socket.ampere.agents.domain.event

import link.socket.ampere.agents.domain.event.EventRegistry.allEventTypes


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
    val allEventTypes: List<EventType> = listOf(
        // Base Event types
        Event.TaskCreated.EVENT_TYPE,
        Event.QuestionRaised.EVENT_TYPE,
        Event.CodeSubmitted.EVENT_TYPE,

        // MeetingEvent types
        MeetingEvent.MeetingScheduled.EVENT_TYPE,
        MeetingEvent.MeetingStarted.EVENT_TYPE,
        MeetingEvent.AgendaItemStarted.EVENT_TYPE,
        MeetingEvent.AgendaItemCompleted.EVENT_TYPE,
        MeetingEvent.MeetingCompleted.EVENT_TYPE,
        MeetingEvent.MeetingCanceled.EVENT_TYPE,

        // TicketEvent types
        TicketEvent.TicketCreated.EVENT_TYPE,
        TicketEvent.TicketStatusChanged.EVENT_TYPE,
        TicketEvent.TicketAssigned.EVENT_TYPE,
        TicketEvent.TicketBlocked.EVENT_TYPE,
        TicketEvent.TicketCompleted.EVENT_TYPE,
        TicketEvent.TicketMeetingScheduled.EVENT_TYPE,

        // MessageEvent types
        MessageEvent.ThreadCreated.EVENT_TYPE,
        MessageEvent.MessagePosted.EVENT_TYPE,
        MessageEvent.ThreadStatusChanged.EVENT_TYPE,
        MessageEvent.EscalationRequested.EVENT_TYPE,

        // NotificationEvent types
        NotificationEvent.ToAgent.EVENT_TYPE,
        NotificationEvent.ToHuman.EVENT_TYPE,

        // MemoryEvent types
        MemoryEvent.KnowledgeStored.EVENT_TYPE,
        MemoryEvent.KnowledgeRecalled.EVENT_TYPE,

        // ToolEvent types
        ToolEvent.ToolRegistered.EVENT_TYPE,
        ToolEvent.ToolUnregistered.EVENT_TYPE,
        ToolEvent.ToolDiscoveryComplete.EVENT_TYPE,

        // FileSystemEvent types
        FileSystemEvent.FileCreated.EVENT_TYPE,
        FileSystemEvent.FileModified.EVENT_TYPE,
        FileSystemEvent.FileDeleted.EVENT_TYPE,

        // ProductEvent types
        ProductEvent.FeatureRequested.EVENT_TYPE,
        ProductEvent.EpicDefined.EVENT_TYPE,
        ProductEvent.PhaseDefined.EVENT_TYPE,
    )

    /**
     * Map of event type names (lowercase) to their EventClassType.
     * Used for parsing command-line arguments.
     */
    val eventTypeByName: Map<String, EventType> by lazy {
        allEventTypes.associateBy { it.lowercase() }
    }

    /**
     * Get all event type names (for display purposes).
     */
    fun getAllEventTypeNames(): Set<String> = eventTypeByName.keys

    /**
     * Parse an event type name to its EventClassType.
     */
    fun parseEventType(typeName: String): EventType? =
        eventTypeByName[typeName.lowercase()]

    /**
     * Parse multiple event type names to a Set of EventClassTypes.
     */
    fun parseEventTypes(typeNames: List<String>): Set<EventType> =
        typeNames.mapNotNull { parseEventType(it) }.toSet()
}
