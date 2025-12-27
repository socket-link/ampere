package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.concept.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.concept.task.MeetingTask
import link.socket.ampere.agents.events.meetings.Meeting

/**
 * Meeting lifecycle events flowing through the EventBus.
 */
@Serializable
sealed interface MeetingEvent : Event {

    /** Emitted when a meeting is scheduled. */
    @Serializable
    data class MeetingScheduled(
        override val eventId: EventId,
        val meeting: Meeting,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent {

        override val eventType: EventType = EVENT_TYPE
        override val timestamp: Instant = meeting.lastUpdatedAt() ?: Instant.DISTANT_PAST

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Meeting scheduled: ${meeting.invitation.title} ${formatUrgency(urgency)} from ${formatSource(
            eventSource,
        )}"

        companion object Companion {
            const val EVENT_TYPE: EventType = "MeetingScheduled"
        }
    }

    /** Emitted when a meeting starts and its discussion thread is available. */
    @Serializable
    data class MeetingStarted(
        override val eventId: EventId,
        val meetingId: String,
        val threadId: String,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Meeting $meetingId started (thread: $threadId) ${formatUrgency(urgency)}"

        companion object Companion {
            const val EVENT_TYPE: EventType = "MeetingStarted"
        }
    }

    /** Emitted when an agenda item begins. */
    @Serializable
    data class AgendaItemStarted(
        override val eventId: EventId,
        val meetingId: String,
        val agendaItem: MeetingTask.AgendaItem,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Agenda item started in meeting $meetingId ${formatUrgency(urgency)}"

        companion object Companion {
            const val EVENT_TYPE: EventType = "AgendaItemStarted"
        }
    }

    /** Emitted when an agenda item completes. */
    @Serializable
    data class AgendaItemCompleted(
        override val eventId: EventId,
        val meetingId: String,
        val agendaItemId: String,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Agenda item $agendaItemId completed in meeting $meetingId ${formatUrgency(urgency)}"

        companion object Companion {
            const val EVENT_TYPE: EventType = "AgendaItemCompleted"
        }
    }

    /** Emitted when a meeting completes with outcomes. */
    @Serializable
    data class MeetingCompleted(
        override val eventId: EventId,
        val meetingId: String,
        val outcomes: List<MeetingOutcome>,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.LOW,
    ) : MeetingEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Meeting $meetingId completed with ${outcomes.size} outcomes ${formatUrgency(urgency)}"

        companion object Companion {
            const val EVENT_TYPE: EventType = "MeetingCompleted"
        }
    }

    /** Emitted when a meeting is canceled. */
    @Serializable
    data class MeetingCanceled(
        override val eventId: EventId,
        val meetingId: String,
        val reason: String,
        override val eventSource: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = "Meeting $meetingId canceled: $reason ${formatUrgency(urgency)}"

        companion object Companion {
            const val EVENT_TYPE: EventType = "MeetingCanceled"
        }
    }
}
