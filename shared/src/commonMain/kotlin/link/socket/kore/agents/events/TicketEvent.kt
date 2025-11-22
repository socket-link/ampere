package link.socket.kore.agents.events

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.kore.agents.core.AgentId
import link.socket.kore.agents.core.AssignedTo
import link.socket.kore.agents.events.meetings.MeetingId
import link.socket.kore.agents.events.tickets.TicketId
import link.socket.kore.agents.events.tickets.TicketPriority
import link.socket.kore.agents.events.tickets.TicketStatus
import link.socket.kore.agents.events.tickets.TicketType

/**
 * Ticket lifecycle events flowing through the EventBus.
 */
@Serializable
sealed class TicketEvent(
    private val source: EventSource,
) : Event {

    override val eventSource: EventSource = source

    /** Emitted when a new ticket is created. */
    @Serializable
    data class TicketCreated(
        override val eventId: EventId,
        val ticketId: TicketId,
        val title: String,
        val description: String,
        val type: TicketType,
        val priority: TicketPriority,
        val createdBy: AgentId,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TicketEvent(source = EventSource.Agent(createdBy)) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object {
            private const val EVENT_TYPE = "TicketCreated"
            val EVENT_CLASS_TYPE: EventClassType = TicketCreated::class to EVENT_TYPE
        }
    }

    /** Emitted when a ticket's status changes. */
    @Serializable
    data class TicketStatusChanged(
        override val eventId: EventId,
        val ticketId: TicketId,
        val previousStatus: TicketStatus,
        val newStatus: TicketStatus,
        val changedBy: AgentId,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TicketEvent(source = EventSource.Agent(changedBy)) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object {
            private const val EVENT_TYPE = "TicketStatusChanged"
            val EVENT_CLASS_TYPE: EventClassType = TicketStatusChanged::class to EVENT_TYPE
        }
    }

    /** Emitted when a ticket is assigned to an agent. */
    @Serializable
    data class TicketAssigned(
        override val eventId: EventId,
        val ticketId: TicketId,
        val assignedTo: AgentId?,
        val assignedBy: AgentId,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TicketEvent(source = EventSource.Agent(assignedBy)) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object {
            private const val EVENT_TYPE = "TicketAssigned"
            val EVENT_CLASS_TYPE: EventClassType = TicketAssigned::class to EVENT_TYPE
        }
    }

    /** Emitted when a ticket becomes blocked. */
    @Serializable
    data class TicketBlocked(
        override val eventId: EventId,
        val ticketId: TicketId,
        val blockingReason: String,
        val reportedBy: AgentId,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.HIGH,
    ) : TicketEvent(source = EventSource.Agent(reportedBy)) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object {
            private const val EVENT_TYPE = "TicketBlocked"
            val EVENT_CLASS_TYPE: EventClassType = TicketBlocked::class to EVENT_TYPE
        }
    }

    /** Emitted when a ticket is completed. */
    @Serializable
    data class TicketCompleted(
        override val eventId: EventId,
        val ticketId: TicketId,
        val completedBy: AgentId,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.LOW,
    ) : TicketEvent(source = EventSource.Agent(completedBy)) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object {
            private const val EVENT_TYPE = "TicketCompleted"
            val EVENT_CLASS_TYPE: EventClassType = TicketCompleted::class to EVENT_TYPE
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
        val scheduledBy: AgentId,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : TicketEvent(source = EventSource.Agent(scheduledBy)) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object {
            private const val EVENT_TYPE = "TicketMeetingScheduled"
            val EVENT_CLASS_TYPE: EventClassType = TicketMeetingScheduled::class to EVENT_TYPE
        }
    }
}
