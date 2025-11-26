package link.socket.ampere.util

import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.MeetingEvent
import link.socket.ampere.agents.events.MessageEvent
import link.socket.ampere.agents.events.NotificationEvent
import link.socket.ampere.agents.events.TicketEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventTypeParserTest {

    @Test
    fun `parse TaskCreated returns correct EventClassType`() {
        val result = EventTypeParser.parse("TaskCreated")
        assertNotNull(result)
        assertEquals(Event.TaskCreated.EVENT_CLASS_TYPE, result)
    }

    @Test
    fun `parse is case-insensitive`() {
        val lowercase = EventTypeParser.parse("taskcreated")
        val uppercase = EventTypeParser.parse("TASKCREATED")
        val mixedcase = EventTypeParser.parse("TaskCreated")

        assertNotNull(lowercase)
        assertNotNull(uppercase)
        assertNotNull(mixedcase)
        assertEquals(lowercase, uppercase)
        assertEquals(uppercase, mixedcase)
    }

    @Test
    fun `parse returns null for invalid event type`() {
        val result = EventTypeParser.parse("InvalidEventType")
        assertNull(result)
    }

    @Test
    fun `parse handles all base Event types`() {
        assertNotNull(EventTypeParser.parse("TaskCreated"))
        assertNotNull(EventTypeParser.parse("QuestionRaised"))
        assertNotNull(EventTypeParser.parse("CodeSubmitted"))
    }

    @Test
    fun `parse handles all MeetingEvent types`() {
        assertNotNull(EventTypeParser.parse("MeetingScheduled"))
        assertNotNull(EventTypeParser.parse("MeetingStarted"))
        assertNotNull(EventTypeParser.parse("AgendaItemStarted"))
        assertNotNull(EventTypeParser.parse("AgendaItemCompleted"))
        assertNotNull(EventTypeParser.parse("MeetingCompleted"))
        assertNotNull(EventTypeParser.parse("MeetingCanceled"))
    }

    @Test
    fun `parse handles all TicketEvent types`() {
        assertNotNull(EventTypeParser.parse("TicketCreated"))
        assertNotNull(EventTypeParser.parse("TicketStatusChanged"))
        assertNotNull(EventTypeParser.parse("TicketAssigned"))
        assertNotNull(EventTypeParser.parse("TicketBlocked"))
        assertNotNull(EventTypeParser.parse("TicketCompleted"))
        assertNotNull(EventTypeParser.parse("TicketMeetingScheduled"))
    }

    @Test
    fun `parse handles all MessageEvent types`() {
        assertNotNull(EventTypeParser.parse("ThreadCreated"))
        assertNotNull(EventTypeParser.parse("MessagePosted"))
        assertNotNull(EventTypeParser.parse("ThreadStatusChanged"))
        assertNotNull(EventTypeParser.parse("EscalationRequested"))
    }

    @Test
    fun `parse handles all NotificationEvent types`() {
        assertNotNull(EventTypeParser.parse("NotificationToAgent"))
        assertNotNull(EventTypeParser.parse("NotificationToHuman"))
    }

    @Test
    fun `parseMultiple returns set of EventClassTypes`() {
        val result = EventTypeParser.parseMultiple(
            listOf("TaskCreated", "QuestionRaised", "MeetingScheduled")
        )

        assertEquals(3, result.size)
        assertTrue(result.contains(Event.TaskCreated.EVENT_CLASS_TYPE))
        assertTrue(result.contains(Event.QuestionRaised.EVENT_CLASS_TYPE))
        assertTrue(result.contains(MeetingEvent.MeetingScheduled.EVENT_CLASS_TYPE))
    }

    @Test
    fun `parseMultiple skips invalid type names`() {
        val result = EventTypeParser.parseMultiple(
            listOf("TaskCreated", "InvalidType", "QuestionRaised")
        )

        assertEquals(2, result.size)
        assertTrue(result.contains(Event.TaskCreated.EVENT_CLASS_TYPE))
        assertTrue(result.contains(Event.QuestionRaised.EVENT_CLASS_TYPE))
    }

    @Test
    fun `parseMultiple returns empty set for all invalid types`() {
        val result = EventTypeParser.parseMultiple(
            listOf("Invalid1", "Invalid2")
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseMultiple handles empty list`() {
        val result = EventTypeParser.parseMultiple(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAllEventTypeNames returns all event types`() {
        val allNames = EventTypeParser.getAllEventTypeNames()

        // Should have at least the basic event types
        assertTrue(allNames.contains("taskcreated"))
        assertTrue(allNames.contains("questionraised"))
        assertTrue(allNames.contains("codesubmitted"))

        // Should have meeting events
        assertTrue(allNames.contains("meetingscheduled"))

        // Should have ticket events
        assertTrue(allNames.contains("ticketcreated"))

        // Should have message events
        assertTrue(allNames.contains("threadcreated"))

        // Should have notification events
        assertTrue(allNames.contains("notificationtoagent"))
        assertTrue(allNames.contains("notificationtohuman"))
    }

    @Test
    fun `getAllEventTypes returns all EventClassTypes`() {
        val allTypes = EventTypeParser.getAllEventTypes()

        // Should have at least the basic event types
        assertTrue(allTypes.contains(Event.TaskCreated.EVENT_CLASS_TYPE))
        assertTrue(allTypes.contains(Event.QuestionRaised.EVENT_CLASS_TYPE))
        assertTrue(allTypes.contains(Event.CodeSubmitted.EVENT_CLASS_TYPE))

        // Should have meeting events
        assertTrue(allTypes.contains(MeetingEvent.MeetingScheduled.EVENT_CLASS_TYPE))

        // Should have ticket events
        assertTrue(allTypes.contains(TicketEvent.TicketCreated.EVENT_CLASS_TYPE))

        // Should have message events
        assertTrue(allTypes.contains(MessageEvent.ThreadCreated.EVENT_CLASS_TYPE))

        // Should have notification events
        assertTrue(allTypes.contains(NotificationEvent.ToAgent.EVENT_CLASS_TYPE))
        assertTrue(allTypes.contains(NotificationEvent.ToHuman.EVENT_CLASS_TYPE))
    }

    @Test
    fun `parsed event type has correct string name`() {
        val taskCreated = EventTypeParser.parse("TaskCreated")
        assertNotNull(taskCreated)
        assertEquals("TaskCreated", taskCreated.second)

        val meetingScheduled = EventTypeParser.parse("MeetingScheduled")
        assertNotNull(meetingScheduled)
        assertEquals("MeetingScheduled", meetingScheduled.second)
    }
}
