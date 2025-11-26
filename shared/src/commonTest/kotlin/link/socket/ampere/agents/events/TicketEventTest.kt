package link.socket.ampere.agents.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.status.TicketStatus
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType

class TicketEventTest {

    private val testAgentId = "test-agent-001"
    private val testTicketId = "ticket-001"

    @Test
    fun `can instantiate all ticket events and access properties`() {
        val now = Clock.System.now()

        val created = TicketEvent.TicketCreated(
            eventId = "11111111-1111-1111-1111-111111111111",
            ticketId = testTicketId,
            title = "Test Ticket",
            description = "A test ticket description",
            type = TicketType.FEATURE,
            priority = TicketPriority.HIGH,
            createdBy = testAgentId,
            timestamp = now,
        )

        val statusChanged = TicketEvent.TicketStatusChanged(
            eventId = "22222222-2222-2222-2222-222222222222",
            ticketId = testTicketId,
            previousStatus = TicketStatus.Backlog,
            newStatus = TicketStatus.Ready,
            changedBy = testAgentId,
            timestamp = now,
        )

        val assigned = TicketEvent.TicketAssigned(
            eventId = "33333333-3333-3333-3333-333333333333",
            ticketId = testTicketId,
            assignedTo = "dev-agent-001",
            assignedBy = testAgentId,
            timestamp = now,
        )

        val blocked = TicketEvent.TicketBlocked(
            eventId = "44444444-4444-4444-4444-444444444444",
            ticketId = testTicketId,
            blockingReason = "Waiting for external dependency",
            reportedBy = testAgentId,
            timestamp = now,
        )

        val completed = TicketEvent.TicketCompleted(
            eventId = "55555555-5555-5555-5555-555555555555",
            ticketId = testTicketId,
            completedBy = testAgentId,
            timestamp = now,
        )

        // Basic property checks
        assertEquals(testTicketId, created.ticketId)
        assertEquals("Test Ticket", created.title)
        assertEquals("A test ticket description", created.description)
        assertEquals(TicketType.FEATURE, created.type)
        assertEquals(TicketPriority.HIGH, created.priority)
        assertEquals(testAgentId, created.createdBy)

        assertEquals(TicketStatus.Backlog, statusChanged.previousStatus)
        assertEquals(TicketStatus.Ready, statusChanged.newStatus)
        assertEquals(testAgentId, statusChanged.changedBy)

        assertEquals("dev-agent-001", assigned.assignedTo)
        assertEquals(testAgentId, assigned.assignedBy)

        assertEquals("Waiting for external dependency", blocked.blockingReason)
        assertEquals(testAgentId, blocked.reportedBy)

        assertEquals(testAgentId, completed.completedBy)

        // Verify eventSource is correctly set
        assertEquals(testAgentId, created.eventSource.getIdentifier())
        assertEquals(testAgentId, statusChanged.eventSource.getIdentifier())
        assertEquals(testAgentId, assigned.eventSource.getIdentifier())
        assertEquals(testAgentId, blocked.eventSource.getIdentifier())
        assertEquals(testAgentId, completed.eventSource.getIdentifier())
    }

    @Test
    fun `event IDs are unique across multiple instantiations`() {
        val now = Clock.System.now()

        val event1 = TicketEvent.TicketCreated(
            eventId = "event-1",
            ticketId = "ticket-1",
            title = "Ticket 1",
            description = "Description 1",
            type = TicketType.FEATURE,
            priority = TicketPriority.HIGH,
            createdBy = testAgentId,
            timestamp = now,
        )

        val event2 = TicketEvent.TicketCreated(
            eventId = "event-2",
            ticketId = "ticket-2",
            title = "Ticket 2",
            description = "Description 2",
            type = TicketType.BUG,
            priority = TicketPriority.CRITICAL,
            createdBy = testAgentId,
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
            type = TicketType.TASK,
            priority = TicketPriority.LOW,
            createdBy = testAgentId,
            timestamp = now,
        )

        val statusChanged = TicketEvent.TicketStatusChanged(
            eventId = "e2",
            ticketId = testTicketId,
            previousStatus = TicketStatus.Ready,
            newStatus = TicketStatus.InProgress,
            changedBy = testAgentId,
            timestamp = now,
        )

        val assigned = TicketEvent.TicketAssigned(
            eventId = "e3",
            ticketId = testTicketId,
            assignedTo = "agent-x",
            assignedBy = testAgentId,
            timestamp = now,
        )

        val blocked = TicketEvent.TicketBlocked(
            eventId = "e4",
            ticketId = testTicketId,
            blockingReason = "Blocked",
            reportedBy = testAgentId,
            timestamp = now,
        )

        val completed = TicketEvent.TicketCompleted(
            eventId = "e5",
            ticketId = testTicketId,
            completedBy = testAgentId,
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
            type = TicketType.FEATURE,
            priority = TicketPriority.MEDIUM,
            createdBy = testAgentId,
            timestamp = now,
        )

        val statusChanged = TicketEvent.TicketStatusChanged(
            eventId = "e2",
            ticketId = testTicketId,
            previousStatus = TicketStatus.Backlog,
            newStatus = TicketStatus.Ready,
            changedBy = testAgentId,
            timestamp = now,
        )

        val assigned = TicketEvent.TicketAssigned(
            eventId = "e3",
            ticketId = testTicketId,
            assignedTo = "agent-y",
            assignedBy = testAgentId,
            timestamp = now,
        )

        val blocked = TicketEvent.TicketBlocked(
            eventId = "e4",
            ticketId = testTicketId,
            blockingReason = "Waiting",
            reportedBy = testAgentId,
            timestamp = now,
        )

        val completed = TicketEvent.TicketCompleted(
            eventId = "e5",
            ticketId = testTicketId,
            completedBy = testAgentId,
            timestamp = now,
        )

        assertEquals(TicketEvent.TicketCreated.EVENT_CLASS_TYPE, created.eventClassType)
        assertEquals(TicketEvent.TicketStatusChanged.EVENT_CLASS_TYPE, statusChanged.eventClassType)
        assertEquals(TicketEvent.TicketAssigned.EVENT_CLASS_TYPE, assigned.eventClassType)
        assertEquals(TicketEvent.TicketBlocked.EVENT_CLASS_TYPE, blocked.eventClassType)
        assertEquals(TicketEvent.TicketCompleted.EVENT_CLASS_TYPE, completed.eventClassType)

        // Verify type names
        assertEquals("TicketCreated", created.eventClassType.second)
        assertEquals("TicketStatusChanged", statusChanged.eventClassType.second)
        assertEquals("TicketAssigned", assigned.eventClassType.second)
        assertEquals("TicketBlocked", blocked.eventClassType.second)
        assertEquals("TicketCompleted", completed.eventClassType.second)
    }

    @Test
    fun `TicketAssigned can have null assignedTo for unassignment`() {
        val now = Clock.System.now()

        val unassigned = TicketEvent.TicketAssigned(
            eventId = "e1",
            ticketId = testTicketId,
            assignedTo = null,
            assignedBy = testAgentId,
            timestamp = now,
        )

        assertEquals(null, unassigned.assignedTo)
        assertEquals(testAgentId, unassigned.assignedBy)
    }

    @Test
    fun `TicketBlocked has HIGH urgency by default`() {
        val now = Clock.System.now()

        val blocked = TicketEvent.TicketBlocked(
            eventId = "e1",
            ticketId = testTicketId,
            blockingReason = "Blocker",
            reportedBy = testAgentId,
            timestamp = now,
        )

        assertEquals(Urgency.HIGH, blocked.urgency)
    }

    @Test
    fun `TicketCompleted has LOW urgency by default`() {
        val now = Clock.System.now()

        val completed = TicketEvent.TicketCompleted(
            eventId = "e1",
            ticketId = testTicketId,
            completedBy = testAgentId,
            timestamp = now,
        )

        assertEquals(Urgency.LOW, completed.urgency)
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
            type = TicketType.FEATURE,
            priority = TicketPriority.HIGH,
            createdBy = testAgentId,
            timestamp = now,
        )

        // This should not throw - EventBus doesn't require registration
        eventSerialBus.publish(event)

        // If we get here without exception, the test passes
        assertTrue(true)
    }
}
