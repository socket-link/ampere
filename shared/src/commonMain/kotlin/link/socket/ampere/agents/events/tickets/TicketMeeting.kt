package link.socket.ampere.agents.events.tickets

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.events.meetings.MeetingId

/**
 * Represents the association between a ticket and a meeting.
 * Used when a ticket requires a decision meeting or discussion.
 */
@Serializable
data class TicketMeeting(
    val ticketId: TicketId,
    val meetingId: MeetingId,
    val createdAt: Instant,
)
