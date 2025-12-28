package link.socket.ampere.agents.events.meetings

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.concept.expectation.MeetingExpectations
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.concept.task.MeetingTask

@Serializable
data class MeetingInvitation(
    val title: String,
    val agenda: List<MeetingTask.AgendaItem>,
    val requiredParticipants: List<AssignedTo>,
    val optionalParticipants: List<AssignedTo>? = null,
    val expectedOutcomes: List<MeetingExpectations>? = null,
)
