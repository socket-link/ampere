package link.socket.ampere.agents.events.meetings

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.status.MeetingStatus

typealias MeetingId = String

/** Aggregate representing a scheduled meeting and its lifecycle state. */
@Serializable
data class Meeting(
    val id: MeetingId,
    val type: MeetingType,
    val status: MeetingStatus,
    val invitation: MeetingInvitation,
    val messagingDetails: MeetingMessagingDetails? = null,
    val creationTriggeredBy: Event? = null,
) {
    fun lastUpdatedAt(): Instant? = when (status) {
        is MeetingStatus.Scheduled -> status.scheduledFor
        is MeetingStatus.Delayed -> status.scheduledFor
        is MeetingStatus.InProgress -> status.startedAt
        is MeetingStatus.Completed -> status.completedAt
        is MeetingStatus.Canceled -> status.canceledAt
    }

    val outcomes: List<MeetingOutcome>
        get() = when (status) {
            is MeetingStatus.Scheduled,
            is MeetingStatus.Delayed,
            is MeetingStatus.InProgress,
            -> emptyList()
            is MeetingStatus.Completed -> status.outcomes ?: emptyList()
            is MeetingStatus.Canceled -> status.outcomes ?: emptyList()
        }
}
