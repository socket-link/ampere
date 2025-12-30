package link.socket.ampere.agents.events.meetings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.domain.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.status.MeetingStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.MeetingTask.AgendaItem

class MeetingEventsTest {

    private val stubEventSource = EventSource.Agent("agent-alpha")
    private val stubEventSource2 = EventSource.Agent("agent-beta")

    private fun sampleMeeting(): Meeting {
        val invitation = MeetingInvitation(
            title = "Daily Standup",
            agenda = listOf(
                AgendaItem(id = "ai-1", title = "Yesterday"),
                AgendaItem(id = "ai-2", title = "Today"),
            ),
            requiredParticipants = listOf(AssignedTo.Agent("agent-1")),
        )
        return Meeting(
            id = "m-1",
            type = MeetingType.AdHoc("sync"),
            status = MeetingStatus.Scheduled(Clock.System.now()),
            invitation = invitation,
        )
    }

    @Test
    fun `can construct all meeting event samples`() {
        val now = Clock.System.now()
        val meeting = sampleMeeting()

        val scheduled: Event = MeetingEvent.MeetingScheduled(
            eventId = "e-1",
            meeting = meeting,
            eventSource = stubEventSource,
        )

        val started: Event = MeetingEvent.MeetingStarted(
            eventId = "e-2",
            meetingId = meeting.id,
            threadId = "thread-1",
            eventSource = stubEventSource2,
            timestamp = now,
        )

        val itemStarted: Event = MeetingEvent.AgendaItemStarted(
            eventId = "e-3",
            meetingId = meeting.id,
            agendaItem = meeting.invitation.agenda.first(),
            eventSource = stubEventSource2,
            timestamp = now,
        )

        val itemCompleted: Event = MeetingEvent.AgendaItemCompleted(
            eventId = "e-4",
            meetingId = meeting.id,
            agendaItemId = meeting.invitation.agenda.first().id,
            eventSource = stubEventSource,
            timestamp = now,
        )

        val completed: Event = MeetingEvent.MeetingCompleted(
            eventId = "e-5",
            meetingId = meeting.id,
            outcomes = listOf(
                MeetingOutcome.DecisionMade("mo-1", "Ship feature", EventSource.Agent("agent-1")),
            ),
            eventSource = stubEventSource,
            timestamp = now,
        )

        val cancelled: Event = MeetingEvent.MeetingCanceled(
            eventId = "e-6",
            meetingId = meeting.id,
            reason = "No quorum",
            eventSource = stubEventSource,
            timestamp = now,
        )

        // Basic assertions that eventClassType discriminators are set
        assertEquals(MeetingEvent.MeetingScheduled.EVENT_TYPE, scheduled.eventType)
        assertEquals(MeetingEvent.MeetingStarted.EVENT_TYPE, started.eventType)
        assertEquals(MeetingEvent.AgendaItemStarted.EVENT_TYPE, itemStarted.eventType)
        assertEquals(MeetingEvent.AgendaItemCompleted.EVENT_TYPE, itemCompleted.eventType)
        assertEquals(MeetingEvent.MeetingCompleted.EVENT_TYPE, completed.eventType)
        assertEquals(MeetingEvent.MeetingCanceled.EVENT_TYPE, cancelled.eventType)
    }
}
