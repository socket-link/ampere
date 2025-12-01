package link.socket.ampere.agents.events.meetings

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.AssignedTo
import link.socket.ampere.agents.core.expectations.MeetingExpectations
import link.socket.ampere.agents.core.tasks.MeetingTask

@Serializable
data class MeetingInvitation(
    val title: String,
    val agenda: List<MeetingTask.AgendaItem>,
    val requiredParticipants: List<AssignedTo>,
    val optionalParticipants: List<AssignedTo>? = null,
    val expectedOutcomes: List<MeetingExpectations>? = null,
)
