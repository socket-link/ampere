package link.socket.ampere.util

import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.EventClassType
import link.socket.ampere.agents.events.MeetingEvent
import link.socket.ampere.agents.events.MessageEvent
import link.socket.ampere.agents.events.NotificationEvent
import link.socket.ampere.agents.events.TicketEvent

/**
 * Utility for parsing event type names from command-line strings to EventClassType.
 *
 * This enables users to filter events by typing event names like "TaskCreated" or "MeetingScheduled".
 */
object EventTypeParser {

    /**
     * Map of event type names (case-insensitive) to their EventClassType.
     *
     * Includes all event types in the system for comprehensive filtering support.
     */
    private val eventTypeMap: Map<String, EventClassType> = mapOf(
        // Base Event types
        "taskcreated" to Event.TaskCreated.EVENT_CLASS_TYPE,
        "questionraised" to Event.QuestionRaised.EVENT_CLASS_TYPE,
        "codesubmitted" to Event.CodeSubmitted.EVENT_CLASS_TYPE,

        // MeetingEvent types
        "meetingscheduled" to MeetingEvent.MeetingScheduled.EVENT_CLASS_TYPE,
        "meetingstarted" to MeetingEvent.MeetingStarted.EVENT_CLASS_TYPE,
        "agendaitemstarted" to MeetingEvent.AgendaItemStarted.EVENT_CLASS_TYPE,
        "agendaitemcompleted" to MeetingEvent.AgendaItemCompleted.EVENT_CLASS_TYPE,
        "meetingcompleted" to MeetingEvent.MeetingCompleted.EVENT_CLASS_TYPE,
        "meetingcanceled" to MeetingEvent.MeetingCanceled.EVENT_CLASS_TYPE,

        // TicketEvent types
        "ticketcreated" to TicketEvent.TicketCreated.EVENT_CLASS_TYPE,
        "ticketstatuschanged" to TicketEvent.TicketStatusChanged.EVENT_CLASS_TYPE,
        "ticketassigned" to TicketEvent.TicketAssigned.EVENT_CLASS_TYPE,
        "ticketblocked" to TicketEvent.TicketBlocked.EVENT_CLASS_TYPE,
        "ticketcompleted" to TicketEvent.TicketCompleted.EVENT_CLASS_TYPE,
        "ticketmeetingscheduled" to TicketEvent.TicketMeetingScheduled.EVENT_CLASS_TYPE,

        // MessageEvent types
        "threadcreated" to MessageEvent.ThreadCreated.EVENT_CLASS_TYPE,
        "messageposted" to MessageEvent.MessagePosted.EVENT_CLASS_TYPE,
        "threadstatuschanged" to MessageEvent.ThreadStatusChanged.EVENT_CLASS_TYPE,
        "escalationrequested" to MessageEvent.EscalationRequested.EVENT_CLASS_TYPE,

        // NotificationEvent types
        "notificationtoagent" to NotificationEvent.ToAgent.EVENT_CLASS_TYPE,
        "notificationtohuman" to NotificationEvent.ToHuman.EVENT_CLASS_TYPE,
    )

    /**
     * Parse a single event type name to its EventClassType.
     *
     * @param typeName The event type name (case-insensitive)
     * @return The EventClassType, or null if not found
     */
    fun parse(typeName: String): EventClassType? {
        return eventTypeMap[typeName.lowercase()]
    }

    /**
     * Parse multiple event type names to a Set of EventClassTypes.
     *
     * @param typeNames List of event type names
     * @return Set of EventClassTypes (skips invalid names)
     */
    fun parseMultiple(typeNames: List<String>): Set<EventClassType> {
        return typeNames.mapNotNull { parse(it) }.toSet()
    }

    /**
     * Get all available event type names.
     *
     * @return Set of all recognized event type names
     */
    fun getAllEventTypeNames(): Set<String> {
        return eventTypeMap.keys
    }

    /**
     * Get all EventClassTypes in the system.
     *
     * @return Set of all EventClassTypes
     */
    fun getAllEventTypes(): Set<EventClassType> {
        return eventTypeMap.values.toSet()
    }
}
