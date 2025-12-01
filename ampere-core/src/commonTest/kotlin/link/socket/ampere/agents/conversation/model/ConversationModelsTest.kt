package link.socket.ampere.agents.conversation.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.events.EventStatus
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.messages.MessageThread

class ConversationModelsTest {

    @Test
    fun `can create conversation with initial message`() {
        val ts: Instant = Clock.System.now()
        val msg = Message(
            id = "m1",
            threadId = "t1",
            sender = MessageSender.Agent("agent-1"),
            content = "Hello",
            timestamp = ts,
        )

        val convo = MessageThread.create(
            id = "c1",
            channel = MessageChannel.Public.Engineering,
            initialMessage = msg,
        )

        assertEquals("c1", convo.id)
        assertEquals(listOf(msg), convo.messages)
        assertEquals(EventStatus.OPEN, convo.status)
        assertEquals(ts, convo.createdAt)
        assertEquals(ts, convo.updatedAt)
        assertEquals("#engineering", convo.channel.getIdentifier())
    }

    @Test
    fun `ThreadStatus prevents invalid transition from RESOLVED to OPEN`() {
        val resolvedToOpen = EventStatus.RESOLVED.canTransitionTo(EventStatus.OPEN)
        assertEquals(false, resolvedToOpen)

        val openToResolved = EventStatus.OPEN.canTransitionTo(EventStatus.RESOLVED)
        assertEquals(true, openToResolved)
    }

    @Test
    fun `addMessage returns new instance with appended message`() {
        val t1 = Clock.System.now()
        val t2 = t1 + 1.seconds

        val m1 = Message(
            id = "m1",
            threadId = "t1",
            sender = MessageSender.Agent("agent-1"),
            content = "Start",
            timestamp = t1,
        )

        val convo1 = MessageThread.create(
            id = "c1",
            channel = MessageChannel.Public.Product,
            initialMessage = m1,
        )

        val m2 = Message(
            id = "m2",
            threadId = "t1",
            sender = MessageSender.Agent("agent-2"),
            content = "Reply",
            timestamp = t2,
        )

        val convo2 = convo1.addMessage(m2)

        // original remains unchanged
        assertEquals(listOf(m1), convo1.messages)

        // new has both messages
        assertEquals(listOf(m1, m2), convo2.messages)
        assertEquals(t2, convo2.updatedAt)
    }
}
