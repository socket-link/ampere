package link.socket.ampere.agents.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType

class TicketEventTest {

    private val testAgentSource = EventSource.Agent("test-agent-001")
    private val testTicketId = "ticket-001"

    @Test
    fun `can instantiate all ticket events and access properties`() {
        val now = Clock.System.now()

        val created = TicketEvent.TicketCreated(
            eventId = "11111111-1111-1111-1111-111111111111",
            ticketId = testTicketId,
            title = "Test Ticket",
            description = "A test ticket description",
            ticketType = TicketType.FEATURE,
            priority = TicketPriority.HIGH,
            eventSource = testAgentSource,
            timestamp = now,
        )

        val statusChanged = TicketEvent.TicketStatusChanged(
            eventId = "22222222-2222-2222-2222-222222222222",
            ticketId = testTicketId,
            previousStatus = TicketStatus.Backlog,
            newStatus = TicketStatus.Ready,
            eventSource = testAgentSource,
            timestamp = now,
        )

        val assigned = TicketEvent.TicketAssigned(
            eventId = "33333333-3333-3333-3333-333333333333",
            ticketId = testTicketId,
            assignedTo = "dev-agent-001",
            eventSource = testAgentSource,
            timestamp = now,
        )

        val blocked = TicketEvent.TicketBlocked(
            eventId = "44444444-4444-4444-4444-444444444444",
            ticketId = testTicketId,
            blockingReason = "Waiting for external dependency",
            eventSource = testAgentSource,
            timestamp = now,
        )

        val completed = TicketEvent.TicketCompleted(
            eventId = "55555555-5555-5555-5555-555555555555",
            ticketId = testTicketId,
            eventSource = testAgentSource,
            timestamp = now,
        )

        // Basic property checks
        assertEquals(testTicketId, created.ticketId)
        assertEquals("Test Ticket", created.title)
        assertEquals("A test ticket description", created.description)
        assertEquals(TicketType.FEATURE, created.ticketType)
        assertEquals(TicketPriority.HIGH, created.priority)
        assertEquals(testAgentSource, created.eventSource)

        assertEquals(TicketStatus.Backlog, statusChanged.previousStatus)
        assertEquals(TicketStatus.Ready, statusChanged.newStatus)
        assertEquals(testAgentSource, created.eventSource)

        assertEquals("dev-agent-001", assigned.assignedTo)
        assertEquals(testAgentSource, created.eventSource)

        assertEquals("Waiting for external dependency", blocked.blockingReason)
        assertEquals(testAgentSource, created.eventSource)

        assertEquals(testAgentSource, created.eventSource)

        // Verify eventSource is correctly set
        assertEquals(testAgentSource.getIdentifier(), created.eventSource.getIdentifier())
        assertEquals(testAgentSource.getIdentifier(), statusChanged.eventSource.getIdentifier())
        assertEquals(testAgentSource.getIdentifier(), assigned.eventSource.getIdentifier())
        assertEquals(testAgentSource.getIdentifier(), blocked.eventSource.getIdentifier())
        assertEquals(testAgentSource.getIdentifier(), completed.eventSource.getIdentifier())
    }

    @Test
    fun `event IDs are unique across multiple instantiations`() {
        val now = Clock.System.now()

        val event1 = TicketEvent.TicketCreated(
            eventId = "event-1",
            ticketId = "ticket-1",
            title = "Ticket 1",
            description = "Description 1",
            ticketType = TicketType.FEATURE,
            priority = TicketPriority.HIGH,
            eventSource = testAgentSource,
            timestamp = now,
        )

        val event2 = TicketEvent.TicketCreated(
            eventId = "event-2",
            ticketId = "ticket-2",
            title = "Ticket 2",
            description = "Description 2",
            ticketType = TicketType.BUG,
            priority = TicketPriority.CRITICAL,
            eventSource = testAgentSource,
            timestamp = now,
        )

        assertNotEquals(event1.eventId, event2.eventId)
    }

    @Test
    fun `exhaustive when expression over sealed class`() {
        val now = Clock.System.now()

        fun handle(event: TicketEvent): String = when (event) {
            is TicketEvent.TicketCreated -> "created"
            is TicketEvent.TicketStatusChanged -> "statusChanged"
            is TicketEvent.TicketAssigned -> "assigned"
            is TicketEvent.TicketBlocked -> "blocked"
            is TicketEvent.TicketCompleted -> "completed"
            is TicketEvent.TicketMeetingScheduled -> "meetingScheduled"
        }

        val created = TicketEvent.TicketCreated(
            eventId = "e1",
            ticketId = testTicketId,
            title = "Test",
            description = "Desc",
            ticketType = TicketType.TASK,
            priority = TicketPriority.LOW,
            eventSource = testAgentSource,
            timestamp = now,
        )

        val statusChanged = TicketEvent.TicketStatusChanged(
            eventId = "e2",
            ticketId = testTicketId,
            previousStatus = TicketStatus.Ready,
            newStatus = TicketStatus.InProgress,
            eventSource = testAgentSource,
            timestamp = now,
        )

        val assigned = TicketEvent.TicketAssigned(
            eventId = "e3",
            ticketId = testTicketId,
            assignedTo = "agent-x",
            eventSource = testAgentSource,
            timestamp = now,
        )

        val blocked = TicketEvent.TicketBlocked(
            eventId = "e4",
            ticketId = testTicketId,
            blockingReason = "Blocked",
            eventSource = testAgentSource,
            timestamp = now,
        )

        val completed = TicketEvent.TicketCompleted(
            eventId = "e5",
            ticketId = testTicketId,
            eventSource = testAgentSource,
            timestamp = now,
        )

        assertEquals("created", handle(created))
        assertEquals("statusChanged", handle(statusChanged))
        assertEquals("assigned", handle(assigned))
        assertEquals("blocked", handle(blocked))
        assertEquals("completed", handle(completed))
    }

    @Test
    fun `eventClassType is correctly set for each event type`() {
        val now = Clock.System.now()

        val created = TicketEvent.TicketCreated(
            eventId = "e1",
            ticketId = testTicketId,
            title = "Test",
            description = "Desc",
            ticketType = TicketType.FEATURE,
            priority = TicketPriority.MEDIUM,
            eventSource = testAgentSource,
            timestamp = now,
        )

        val statusChanged = TicketEvent.TicketStatusChanged(
            eventId = "e2",
            ticketId = testTicketId,
            previousStatus = TicketStatus.Backlog,
            newStatus = TicketStatus.Ready,
            eventSource = testAgentSource,
            timestamp = now,
        )

        val assigned = TicketEvent.TicketAssigned(
            eventId = "e3",
            ticketId = testTicketId,
            assignedTo = "agent-y",
            eventSource = testAgentSource,
            timestamp = now,
        )

        val blocked = TicketEvent.TicketBlocked(
            eventId = "e4",
            ticketId = testTicketId,
            blockingReason = "Waiting",
            eventSource = testAgentSource,
            timestamp = now,
        )

        val completed = TicketEvent.TicketCompleted(
            eventId = "e5",
            ticketId = testTicketId,
            eventSource = testAgentSource,
            timestamp = now,
        )

        assertEquals(TicketEvent.TicketCreated.EVENT_TYPE, created.eventType)
        assertEquals(TicketEvent.TicketStatusChanged.EVENT_TYPE, statusChanged.eventType)
        assertEquals(TicketEvent.TicketAssigned.EVENT_TYPE, assigned.eventType)
        assertEquals(TicketEvent.TicketBlocked.EVENT_TYPE, blocked.eventType)
        assertEquals(TicketEvent.TicketCompleted.EVENT_TYPE, completed.eventType)

        // Verify type names
        assertEquals("TicketCreated", created.eventType)
        assertEquals("TicketStatusChanged", statusChanged.eventType)
        assertEquals("TicketAssigned", assigned.eventType)
        assertEquals("TicketBlocked", blocked.eventType)
        assertEquals("TicketCompleted", completed.eventType)
    }

    @Test
    fun `TicketAssigned can have null assignedTo for unassignment`() {
        val now = Clock.System.now()

        val unassigned = TicketEvent.TicketAssigned(
            eventId = "e1",
            ticketId = testTicketId,
            assignedTo = null,
            eventSource = testAgentSource,
            timestamp = now,
        )

        assertEquals(null, unassigned.assignedTo)
        assertEquals(testAgentSource, unassigned.eventSource)
    }

    @Test
    fun `TicketBlocked has HIGH urgency by default`() {
        val now = Clock.System.now()

        val blocked = TicketEvent.TicketBlocked(
            eventId = "e1",
            ticketId = testTicketId,
            blockingReason = "Blocker",
            eventSource = testAgentSource,
            timestamp = now,
        )

        assertEquals(Urgency.HIGH, blocked.urgency)
        assertEquals(testAgentSource, blocked.eventSource)
    }

    @Test
    fun `TicketCompleted has LOW urgency by default`() {
        val now = Clock.System.now()

        val completed = TicketEvent.TicketCompleted(
            eventId = "e1",
            ticketId = testTicketId,
            eventSource = testAgentSource,
            timestamp = now,
        )

        assertEquals(Urgency.LOW, completed.urgency)
        assertEquals(testAgentSource, completed.eventSource)
    }

    @Test
    fun `publishing TicketCreated event to EventBus does not throw`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default)
        val eventSerialBus = EventSerialBus(scope)

        val now = Clock.System.now()
        val event = TicketEvent.TicketCreated(
            eventId = "e1",
            ticketId = testTicketId,
            title = "Test",
            description = "Desc",
            ticketType = TicketType.FEATURE,
            priority = TicketPriority.HIGH,
            eventSource = testAgentSource,
            timestamp = now,
        )

        // This should not throw - EventBus doesn't require registration
        eventSerialBus.publish(event)

        // If we get here without exception, the test passes
        assertTrue(true)
    }
}
