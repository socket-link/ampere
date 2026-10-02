package link.socket.ampere.standup

import link.socket.ampere.agents.events.meetings.MeetingId
import link.socket.ampere.agents.events.messages.MessageId

/**
 * What a standup produced (AMPR-379).
 *
 * @property meetingId The Meeting the standup ran as; its `MeetingEvent.MeetingStarted`
 *   and `MeetingCompleted` carry this id.
 * @property revisionProposal The plan revision the roster converged on.
 * @property revisionReleased Whether the Inspector's review released the revision
 *   card into the Room. A withheld revision is also an escalation.
 * @property statusPost The Coordinator's status post in the Room's general thread.
 * @property narrative The text of that post — the one metered step, or the template.
 * @property escalations What the human has to settle. Empty is the good week.
 */
data class StandupOutcome(
    val meetingId: MeetingId,
    val revisionProposal: RevisionProposal,
    val revisionReleased: Boolean,
    val statusPost: MessageId,
    val narrative: String,
    val escalations: List<StandupEscalation>,
)
