package link.socket.ampere.agents.events

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.AssignedTo
import link.socket.ampere.agents.core.status.TicketStatus
import link.socket.ampere.agents.events.meetings.MeetingId
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType

/**
 * Ticket lifecycle events flowing through the EventBus.
 */
@Serializable
sealed interface TicketEvent : Event {

    /** Emitted when a new ticket is created. */
    @Serializable
    data class TicketCreated(
        override val eventId: EventId,
        val ticketId: TicketId,
        val title: String,
        val description: String,
        val ticketType: TicketType,
        val priority: TicketPriority,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TicketEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "TicketCreated"
        }
    }

    /** Emitted when a ticket's status changes. */
    @Serializable
    data class TicketStatusChanged(
        override val eventId: EventId,
        val ticketId: TicketId,
        val previousStatus: TicketStatus,
        val newStatus: TicketStatus,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TicketEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "TicketStatusChanged"
        }
    }

    /** Emitted when a ticket is assigned to an agent. */
    @Serializable
    data class TicketAssigned(
        override val eventId: EventId,
        val ticketId: TicketId,
        val assignedTo: AgentId?,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TicketEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "TicketAssigned"
        }
    }

    /** Emitted when a ticket becomes blocked. */
    @Serializable
    data class TicketBlocked(
        override val eventId: EventId,
        val ticketId: TicketId,
        val blockingReason: String,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.HIGH,
    ) : TicketEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "TicketBlocked"
        }
    }

    /** Emitted when a ticket is completed. */
    @Serializable
    data class TicketCompleted(
        override val eventId: EventId,
        val ticketId: TicketId,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.LOW,
    ) : TicketEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "TicketCompleted"
        }
    }

    /** Emitted when a meeting is scheduled to specifically discuss one ticket. */
    @Serializable
    data class TicketMeetingScheduled(
        override val eventId: EventId,
        val ticketId: TicketId,
        val meetingId: MeetingId,
        val scheduledTime: Instant,
        val requiredParticipants: List<AssignedTo>,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TicketEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "TicketMeetingScheduled"
        }
    }
}
