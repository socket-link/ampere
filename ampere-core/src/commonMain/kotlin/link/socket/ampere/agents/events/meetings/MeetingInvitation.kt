package link.socket.ampere.agents.events.meetings

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.expectation.MeetingExpectation
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.MeetingTask

@Serializable
data class MeetingInvitation(
    val title: String,
    val agenda: List<MeetingTask.AgendaItem>,
    val requiredParticipants: List<AssignedTo>,
    val optionalParticipants: List<AssignedTo>? = null,
    val expectedOutcomes: List<MeetingExpectation>? = null,
)
