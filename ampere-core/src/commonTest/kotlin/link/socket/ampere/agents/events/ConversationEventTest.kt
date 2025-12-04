package link.socket.ampere.agents.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.status.EventStatus
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.toEventSource

class ConversationEventTest {

    private val stubSenderA = MessageSender.Agent(agentId = "agentA")
    private val stubSenderB = MessageSender.Agent(agentId = "agentB")
    private val stubThreadId = "t1"
    private val stubChannel = MessageChannel.Public.Engineering

    @Test
    fun `can instantiate all conversation events and access properties`() {
        val now = Clock.System.now()

        val initialMessage = Message(
            id = "m1",
            threadId = "t1",
            sender = stubSenderA,
            content = "Hello",
            timestamp = now,
            metadata = mapOf("k" to "v"),
        )

        val created = MessageEvent.ThreadCreated(
            eventId = "11111111-1111-1111-1111-111111111111",
            thread = MessageThread.create(stubThreadId, stubChannel, initialMessage),
        )

        val followupMessage = Message(
            id = "m2",
            threadId = "t1",
            sender = stubSenderB,
            content = "Hi there",
            timestamp = now + 1.seconds,
        )

        val posted = MessageEvent.MessagePosted(
            eventId = "22222222-2222-2222-2222-222222222222",
            threadId = stubThreadId,
            channel = stubChannel,
            message = followupMessage,
        )

        val statusChanged = MessageEvent.ThreadStatusChanged(
            eventId = "33333333-3333-3333-3333-333333333333",
            timestamp = now + 2.seconds,
            eventSource = stubSenderA.toEventSource(),
            threadId = stubThreadId,
            oldStatus = EventStatus.Open,
            newStatus = EventStatus.WaitingForHuman,
        )

        val escalation = MessageEvent.EscalationRequested(
            eventId = "44444444-4444-4444-4444-444444444444",
            timestamp = now + 3.seconds,
            eventSource = stubSenderA.toEventSource(),
            threadId = stubThreadId,
            reason = "Needs human input",
            context = mapOf("priority" to "high"),
        )

        // Basic property checks
        assertEquals("t1", created.threadId)
        assertEquals(initialMessage, created.thread.messages.first())
        assertEquals(MessageChannel.Public.Engineering, posted.channel)
        assertEquals(EventStatus.Open, statusChanged.oldStatus)
        assertEquals("agentA", escalation.eventSource.getIdentifier())

        // Exhaustive when expression over sealed interface
        fun handle(event: MessageEvent): String = when (event) {
            is MessageEvent.ThreadCreated -> "created"
            is MessageEvent.MessagePosted -> "posted"
            is MessageEvent.ThreadStatusChanged -> "status"
            is MessageEvent.EscalationRequested -> "escalation"
        }

        assertEquals("created", handle(created))
        assertEquals("posted", handle(posted))
        assertEquals("status", handle(statusChanged))
        assertEquals("escalation", handle(escalation))
    }
}
