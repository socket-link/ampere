package link.socket.ampere.agents.events.meetings

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.PRId
import link.socket.ampere.agents.domain.SprintId
import link.socket.ampere.agents.domain.TeamId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.task.AssignedTo

/** Types of meetings supported by the system. */
@Serializable
sealed interface MeetingType {

    @Serializable
    data class Standup(
        val teamId: TeamId,
        val sprintId: SprintId,
    ) : MeetingType

    @Serializable
    data class SprintPlanning(
        val teamId: TeamId,
        val sprintId: SprintId,
    ) : MeetingType

    @Serializable
    data class CodeReview(
        val prId: PRId,
        val prUrl: String,
        val author: EventSource,
        val requestedReviewer: AssignedTo.Agent,
    ) : MeetingType

    @Serializable
    data class AdHoc(
        val reason: String,
    ) : MeetingType
}
