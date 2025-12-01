package link.socket.ampere.agents.core.status

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.outcomes.MeetingOutcome
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.meetings.MeetingMessagingDetails

/** Lifecycle of the status for a meeting. */
@Serializable
sealed interface MeetingStatus : Status {

    val scheduledFor: Instant?
        get() = null

    @Serializable
    data class Scheduled(
        override val scheduledFor: Instant,
    ) : MeetingStatus {

        override val isClosed: Boolean = false
    }

    @Serializable
    data class Delayed(
        val reason: String,
        override val scheduledFor: Instant? = null,
    ) : MeetingStatus {

        override val isClosed: Boolean = false
    }

    @Serializable
    data class InProgress(
        val startedAt: Instant,
        val messagingDetails: MeetingMessagingDetails,
        override val scheduledFor: Instant? = null,
    ) : MeetingStatus {

        override val isClosed: Boolean = false
    }

    @Serializable
    data class Completed(
        val completedAt: Instant,
        val attendedBy: List<EventSource>,
        val messagingDetails: MeetingMessagingDetails,
        override val scheduledFor: Instant? = null,
        val outcomes: List<MeetingOutcome>? = null,
    ) : MeetingStatus {

        override val isClosed: Boolean = true
    }

    @Serializable
    data class Canceled(
        val reason: String,
        val canceledAt: Instant,
        val messagingDetails: MeetingMessagingDetails,
        override val scheduledFor: Instant? = null,
        val outcomes: List<MeetingOutcome>? = null,
    ) : MeetingStatus {

        override val isClosed: Boolean = true
    }
}
