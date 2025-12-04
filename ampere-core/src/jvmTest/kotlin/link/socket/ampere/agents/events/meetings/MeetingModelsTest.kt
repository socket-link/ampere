package link.socket.ampere.agents.events.meetings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.concept.expectation.MeetingExpectations
import link.socket.ampere.agents.domain.concept.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.concept.status.MeetingStatus
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.task.MeetingTask.AgendaItem
import link.socket.ampere.agents.domain.event.EventSource

class MeetingModelsTest {

    val stubEventSource = EventSource.Agent("agent-alpha")
    val stubAssignedTo = AssignedTo.Agent("agent-alpha")

    fun createAgendaItem(
        id: String,
        topic: String,
        assignedTo: AssignedTo.Agent? = null,
        status: TaskStatus = TaskStatus.Pending,
    ) = AgendaItem(
        id = id,
        title = topic,
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

        val outcomeRequirements = MeetingExpectations(
            requirementsDescription = "Implement new API",
            expectedOutcomes = listOf(
                MeetingOutcome.ActionItem::class,
                MeetingOutcome.DecisionMade::class,
            ),
        )

        val outcomes = listOf(
            MeetingOutcome.DecisionMade(
                id = "mo-1",
                description = "Proceed with refactor",
                decidedBy = stubEventSource,
            ),
            MeetingOutcome.ActionItem(
                id = "mo-2",
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
                expectedOutcomes = listOf(outcomeRequirements),
            ),
        )

        assert(meeting.type is MeetingType.Standup)
        assert(meeting.status is MeetingStatus.Completed)
        assertEquals(2, meeting.invitation.agenda.size)
        assertEquals(2, meeting.outcomes.size)
    }
}
