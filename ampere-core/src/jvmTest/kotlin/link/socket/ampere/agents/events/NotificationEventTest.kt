package link.socket.ampere.agents.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.subscription.Subscription

class NotificationEventTest {

    @Test
    fun `to agent mirrors source event properties`() {
        val source = Event.TaskCreated(
            eventId = "e-1",
            urgency = Urgency.HIGH,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("author"),
            taskId = "t-1",
            description = "desc",
            assignedTo = null,
        )

        val n: NotificationEvent.ToAgent<Subscription> = NotificationEvent.ToAgent(
            agentId = "agent-42",
            event = source,
            subscription = null,
        )

        assertEquals("agent-42", (n.eventSource as EventSource.Agent).agentId)
        assertTrue(n.eventId.contains("e-1"))
        assertEquals(source.timestamp, n.timestamp)
        assertEquals(source.urgency, n.urgency)
        assertEquals(NotificationEvent.ToAgent.EVENT_TYPE, n.eventType)
    }

    @Test
    fun `to human mirrors source event properties`() {
        val source = Event.QuestionRaised(
            eventId = "e-2",
            urgency = Urgency.MEDIUM,
            timestamp = Clock.System.now(),
            eventSource = EventSource.Agent("author"),
            questionText = "Q?",
            context = "ctx",
        )

        val n: NotificationEvent.ToHuman<Subscription> = NotificationEvent.ToHuman(
            event = source,
            subscription = null,
        )

        assertIs<EventSource.Human>(n.eventSource)
        assertTrue(n.eventId.contains("e-2"))
        assertEquals(source.timestamp, n.timestamp)
        assertEquals(source.urgency, n.urgency)
        assertEquals(NotificationEvent.ToHuman.EVENT_TYPE, n.eventType)

        // Note: serialization of generic sealed classes can vary by platform; core behavior validated above.
    }
}
