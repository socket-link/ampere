package link.socket.ampere.agents.events.meetings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AssignedTo
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.tasks.AgendaItem
import link.socket.ampere.agents.events.tasks.Task

class MeetingModelsTest {

    val stubEventSource = EventSource.Agent("agent-alpha")
    val stubAssignedTo = AssignedTo.Agent("agent-alpha")

    fun createAgendaItem(
        id: String,
        topic: String,
        assignedTo: AssignedTo.Agent? = null,
        status: Task.Status = Task.Status.Pending(),
    ) = AgendaItem(
        id = id,
        topic = topic,
        status = status,
        assignedTo = assignedTo,
    )

    @Test
    fun `can construct meeting with agenda and outcomes`() {
        val now = Clock.System.now()
        val agenda = listOf(
            createAgendaItem(
                id = "ai-0",
                topic = "Yesterday updates",
            ),
            createAgendaItem(
                id = "ai-0",
                topic = "Today Plans",
            ),
        )

        val outcomeRequirements = MeetingOutcomeRequirements(
            requirementsDescription = "Implement new API",
            expectedOutcomes = listOf(
                MeetingOutcome.Type.ACTION_ITEM,
                MeetingOutcome.Type.DECISION_MADE,
            ),
        )

        val outcomes = listOf(
            MeetingOutcome.DecisionMade(
                overrideId = "mo-1",
                description = "Proceed with refactor",
                decidedBy = stubEventSource,
            ),
            MeetingOutcome.ActionItem(
                overrideId = "mo-2",
                assignedTo = stubAssignedTo,
                description = "Implement new API",
                dueBy = now + 1.seconds,
            ),
        )

        val meeting = Meeting(
            id = "m-1",
            type = MeetingType.Standup(
                teamId = "team-1",
                sprintId = "sprint-1",
            ),
            status = MeetingStatus.Completed(
                completedAt = now,
                attendedBy = listOf(stubEventSource),
                outcomes = outcomes,
                messagingDetails = MeetingMessagingDetails(
                    messageChannelId = "channel-1",
                    messageThreadId = "thread-1",
                ),
            ),
            invitation = MeetingInvitation(
                title = "Daily Standup",
                agenda = agenda,
                requiredParticipants = listOf(stubAssignedTo),
                optionalParticipants = listOf(stubAssignedTo),
            ),
        )

        assert(meeting.type is MeetingType.Standup)
        assert(meeting.status is MeetingStatus.Completed)
        assertEquals(2, meeting.invitation.agenda.size)
        assertEquals(2, meeting.outcomes.size)
    }
}
