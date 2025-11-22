package link.socket.kore.agents.events

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.kore.agents.events.meetings.Meeting
import link.socket.kore.agents.events.meetings.MeetingOutcome
import link.socket.kore.agents.events.tasks.AgendaItem

/**
 * Meeting lifecycle events flowing through the EventBus.
 */
@Serializable
sealed class MeetingEvent(
    private val source: EventSource,
) : Event {

    override val eventSource: EventSource = source

    /** Emitted when a meeting is scheduled. */
    @Serializable
    data class MeetingScheduled(
        override val eventId: EventId,
        val meeting: Meeting,
        val scheduledBy: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent(source = scheduledBy) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        @Transient
        override val timestamp: Instant = meeting.lastUpdatedAt() ?: Instant.DISTANT_PAST

        companion object Companion {
            private const val EVENT_TYPE = "MeetingScheduled"
            val EVENT_CLASS_TYPE: EventClassType = MeetingScheduled::class to EVENT_TYPE
        }
    }

    /** Emitted when a meeting starts and its discussion thread is available. */
    @Serializable
    data class MeetingStarted(
        override val eventId: EventId,
        val meetingId: String,
        val threadId: String,
        val startedAt: Instant,
        val startedBy: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent(source = startedBy) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        @Transient
        override val timestamp: Instant = startedAt

        companion object Companion {
            private const val EVENT_TYPE = "MeetingStarted"
            val EVENT_CLASS_TYPE: EventClassType = MeetingStarted::class to EVENT_TYPE
        }
    }

    /** Emitted when an agenda item begins. */
    @Serializable
    data class AgendaItemStarted(
        override val eventId: EventId,
        val meetingId: String,
        val agendaItem: AgendaItem,
        val startedBy: EventSource,
        override val timestamp: Instant,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent(source = startedBy) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object Companion {
            private const val EVENT_TYPE = "AgendaItemStarted"
            val EVENT_CLASS_TYPE: EventClassType = AgendaItemStarted::class to EVENT_TYPE
        }
    }

    /** Emitted when an agenda item completes. */
    @Serializable
    data class AgendaItemCompleted(
        override val eventId: EventId,
        val meetingId: String,
        val agendaItemId: String,
        val completedAt: Instant,
        val completedBy: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent(source = completedBy) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        @Transient
        override val timestamp: Instant = completedAt

        companion object Companion {
            private const val EVENT_TYPE = "AgendaItemCompleted"
            val EVENT_CLASS_TYPE: EventClassType = AgendaItemCompleted::class to EVENT_TYPE
        }
    }

    /** Emitted when a meeting completes with outcomes. */
    @Serializable
    data class MeetingCompleted(
        override val eventId: EventId,
        val meetingId: String,
        val outcomes: List<MeetingOutcome>,
        val completedAt: Instant,
        val completedBy: EventSource,
        override val urgency: Urgency = Urgency.LOW,
    ) : MeetingEvent(source = completedBy) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        @Transient
        override val timestamp: Instant = completedAt

        companion object Companion {
            private const val EVENT_TYPE = "MeetingCompleted"
            val EVENT_CLASS_TYPE: EventClassType = MeetingCompleted::class to EVENT_TYPE
        }
    }

    /** Emitted when a meeting is canceled. */
    @Serializable
    data class MeetingCanceled(
        override val eventId: EventId,
        val meetingId: String,
        val reason: String,
        val canceledAt: Instant,
        val canceledBy: EventSource,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MeetingEvent(source = canceledBy) {

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        @Transient
        override val timestamp: Instant = canceledAt

        companion object Companion {
            private const val EVENT_TYPE = "MeetingCanceled"
            val EVENT_CLASS_TYPE: EventClassType = MeetingCanceled::class to EVENT_TYPE
        }
    }
}
