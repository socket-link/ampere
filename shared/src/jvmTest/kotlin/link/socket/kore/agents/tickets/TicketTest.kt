package link.socket.kore.agents.tickets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.kore.agents.events.tickets.Ticket
import link.socket.kore.agents.events.tickets.TicketPriority
import link.socket.kore.agents.events.tickets.TicketStatus
import link.socket.kore.agents.events.tickets.TicketType

class TicketTest {

    private val now = Clock.System.now()
    private val creatorAgentId = "pm-agent-1"
    private val assigneeAgentId = "eng-agent-1"

    private fun createTicket(
        status: TicketStatus = TicketStatus.BACKLOG,
        type: TicketType = TicketType.FEATURE,
        priority: TicketPriority = TicketPriority.MEDIUM,
    ): Ticket = Ticket(
        id = "ticket-1",
        title = "Test Ticket",
        description = "Test Description",
        type = type,
        priority = priority,
        status = status,
        assignedAgentId = null,
        createdByAgentId = creatorAgentId,
        createdAt = now,
        updatedAt = now,
        dueDate = null,
    )

    // =============================================================================
    // TICKET INSTANTIATION TESTS
    // =============================================================================

    @Test
    fun `can create ticket with BACKLOG status`() {
        val ticket = createTicket(status = TicketStatus.BACKLOG)
        assertEquals(TicketStatus.BACKLOG, ticket.status)
    }

    @Test
    fun `can create ticket with READY status`() {
        val ticket = createTicket(status = TicketStatus.READY)
        assertEquals(TicketStatus.READY, ticket.status)
    }

    @Test
    fun `can create ticket with IN_PROGRESS status`() {
        val ticket = createTicket(status = TicketStatus.IN_PROGRESS)
        assertEquals(TicketStatus.IN_PROGRESS, ticket.status)
    }

    @Test
    fun `can create ticket with BLOCKED status`() {
        val ticket = createTicket(status = TicketStatus.BLOCKED)
        assertEquals(TicketStatus.BLOCKED, ticket.status)
    }

    @Test
    fun `can create ticket with IN_REVIEW status`() {
        val ticket = createTicket(status = TicketStatus.IN_REVIEW)
        assertEquals(TicketStatus.IN_REVIEW, ticket.status)
    }

    @Test
    fun `can create ticket with DONE status`() {
        val ticket = createTicket(status = TicketStatus.DONE)
        assertEquals(TicketStatus.DONE, ticket.status)
    }

    // =============================================================================
    // ENUM CONSTRAINT TESTS
    // =============================================================================

    @Test
    fun `TicketType enum has all expected values`() {
        val values = TicketType.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(TicketType.FEATURE))
        assertTrue(values.contains(TicketType.BUG))
        assertTrue(values.contains(TicketType.TASK))
        assertTrue(values.contains(TicketType.SPIKE))
    }

    @Test
    fun `TicketPriority enum has all expected values`() {
        val values = TicketPriority.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(TicketPriority.LOW))
        assertTrue(values.contains(TicketPriority.MEDIUM))
        assertTrue(values.contains(TicketPriority.HIGH))
        assertTrue(values.contains(TicketPriority.CRITICAL))
    }

    @Test
    fun `TicketStatus enum has all expected values`() {
        val values = TicketStatus.entries
        assertEquals(6, values.size)
        assertTrue(values.contains(TicketStatus.BACKLOG))
        assertTrue(values.contains(TicketStatus.READY))
        assertTrue(values.contains(TicketStatus.IN_PROGRESS))
        assertTrue(values.contains(TicketStatus.BLOCKED))
        assertTrue(values.contains(TicketStatus.IN_REVIEW))
        assertTrue(values.contains(TicketStatus.DONE))
    }

    @Test
    fun `can create ticket with each TicketType`() {
        TicketType.entries.forEach { type ->
            val ticket = createTicket(type = type)
            assertEquals(type, ticket.type)
        }
    }

    @Test
    fun `can create ticket with each TicketPriority`() {
        TicketPriority.entries.forEach { priority ->
            val ticket = createTicket(priority = priority)
            assertEquals(priority, ticket.priority)
        }
    }

    // =============================================================================
    // VALID STATE TRANSITION TESTS
    // =============================================================================

    @Test
    fun `BACKLOG can transition to READY`() {
        val ticket = createTicket(status = TicketStatus.BACKLOG)
        assertTrue(ticket.canTransitionTo(TicketStatus.READY))
        val updated = ticket.transitionTo(TicketStatus.READY, now)
        assertEquals(TicketStatus.READY, updated.status)
    }

    @Test
    fun `BACKLOG can transition to DONE`() {
        val ticket = createTicket(status = TicketStatus.BACKLOG)
        assertTrue(ticket.canTransitionTo(TicketStatus.DONE))
        val updated = ticket.transitionTo(TicketStatus.DONE, now)
        assertEquals(TicketStatus.DONE, updated.status)
    }

    @Test
    fun `READY can transition to IN_PROGRESS`() {
        val ticket = createTicket(status = TicketStatus.READY)
        assertTrue(ticket.canTransitionTo(TicketStatus.IN_PROGRESS))
        val updated = ticket.transitionTo(TicketStatus.IN_PROGRESS, now)
        assertEquals(TicketStatus.IN_PROGRESS, updated.status)
    }

    @Test
    fun `IN_PROGRESS can transition to BLOCKED`() {
        val ticket = createTicket(status = TicketStatus.IN_PROGRESS)
        assertTrue(ticket.canTransitionTo(TicketStatus.BLOCKED))
        val updated = ticket.transitionTo(TicketStatus.BLOCKED, now)
        assertEquals(TicketStatus.BLOCKED, updated.status)
    }

    @Test
    fun `IN_PROGRESS can transition to IN_REVIEW`() {
        val ticket = createTicket(status = TicketStatus.IN_PROGRESS)
        assertTrue(ticket.canTransitionTo(TicketStatus.IN_REVIEW))
        val updated = ticket.transitionTo(TicketStatus.IN_REVIEW, now)
        assertEquals(TicketStatus.IN_REVIEW, updated.status)
    }

    @Test
    fun `IN_PROGRESS can transition to DONE`() {
        val ticket = createTicket(status = TicketStatus.IN_PROGRESS)
        assertTrue(ticket.canTransitionTo(TicketStatus.DONE))
        val updated = ticket.transitionTo(TicketStatus.DONE, now)
        assertEquals(TicketStatus.DONE, updated.status)
    }

    @Test
    fun `BLOCKED can transition back to IN_PROGRESS`() {
        val ticket = createTicket(status = TicketStatus.BLOCKED)
        assertTrue(ticket.canTransitionTo(TicketStatus.IN_PROGRESS))
        val updated = ticket.transitionTo(TicketStatus.IN_PROGRESS, now)
        assertEquals(TicketStatus.IN_PROGRESS, updated.status)
    }

    @Test
    fun `IN_REVIEW can transition to IN_PROGRESS`() {
        val ticket = createTicket(status = TicketStatus.IN_REVIEW)
        assertTrue(ticket.canTransitionTo(TicketStatus.IN_PROGRESS))
        val updated = ticket.transitionTo(TicketStatus.IN_PROGRESS, now)
        assertEquals(TicketStatus.IN_PROGRESS, updated.status)
    }

    @Test
    fun `IN_REVIEW can transition to DONE`() {
        val ticket = createTicket(status = TicketStatus.IN_REVIEW)
        assertTrue(ticket.canTransitionTo(TicketStatus.DONE))
        val updated = ticket.transitionTo(TicketStatus.DONE, now)
        assertEquals(TicketStatus.DONE, updated.status)
    }

    // =============================================================================
    // INVALID STATE TRANSITION TESTS
    // =============================================================================

    @Test
    fun `BACKLOG cannot transition to IN_PROGRESS`() {
        val ticket = createTicket(status = TicketStatus.BACKLOG)
        assertFalse(ticket.canTransitionTo(TicketStatus.IN_PROGRESS))
    }

    @Test
    fun `BACKLOG cannot transition to IN_REVIEW`() {
        val ticket = createTicket(status = TicketStatus.BACKLOG)
        assertFalse(ticket.canTransitionTo(TicketStatus.IN_REVIEW))
        assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.IN_REVIEW, now)
        }
    }

    @Test
    fun `BACKLOG cannot transition to BLOCKED`() {
        val ticket = createTicket(status = TicketStatus.BACKLOG)
        assertFalse(ticket.canTransitionTo(TicketStatus.BLOCKED))
        assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.BLOCKED, now)
        }
    }

    @Test
    fun `READY cannot transition to DONE directly`() {
        val ticket = createTicket(status = TicketStatus.READY)
        assertFalse(ticket.canTransitionTo(TicketStatus.DONE))
        assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.DONE, now)
        }
    }

    @Test
    fun `READY cannot transition to BLOCKED`() {
        val ticket = createTicket(status = TicketStatus.READY)
        assertFalse(ticket.canTransitionTo(TicketStatus.BLOCKED))
    }

    @Test
    fun `READY cannot transition to IN_REVIEW`() {
        val ticket = createTicket(status = TicketStatus.READY)
        assertFalse(ticket.canTransitionTo(TicketStatus.IN_REVIEW))
    }

    @Test
    fun `READY cannot transition back to BACKLOG`() {
        val ticket = createTicket(status = TicketStatus.READY)
        assertFalse(ticket.canTransitionTo(TicketStatus.BACKLOG))
    }

    @Test
    fun `IN_PROGRESS cannot transition to BACKLOG`() {
        val ticket = createTicket(status = TicketStatus.IN_PROGRESS)
        assertFalse(ticket.canTransitionTo(TicketStatus.BACKLOG))
    }

    @Test
    fun `IN_PROGRESS cannot transition to READY`() {
        val ticket = createTicket(status = TicketStatus.IN_PROGRESS)
        assertFalse(ticket.canTransitionTo(TicketStatus.READY))
    }

    @Test
    fun `BLOCKED cannot transition to DONE`() {
        val ticket = createTicket(status = TicketStatus.BLOCKED)
        assertFalse(ticket.canTransitionTo(TicketStatus.DONE))
        assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.DONE, now)
        }
    }

    @Test
    fun `BLOCKED cannot transition to IN_REVIEW`() {
        val ticket = createTicket(status = TicketStatus.BLOCKED)
        assertFalse(ticket.canTransitionTo(TicketStatus.IN_REVIEW))
    }

    @Test
    fun `IN_REVIEW cannot transition to BLOCKED`() {
        val ticket = createTicket(status = TicketStatus.IN_REVIEW)
        assertFalse(ticket.canTransitionTo(TicketStatus.BLOCKED))
    }

    @Test
    fun `IN_REVIEW cannot transition to READY`() {
        val ticket = createTicket(status = TicketStatus.IN_REVIEW)
        assertFalse(ticket.canTransitionTo(TicketStatus.READY))
    }

    @Test
    fun `IN_REVIEW cannot transition to BACKLOG`() {
        val ticket = createTicket(status = TicketStatus.IN_REVIEW)
        assertFalse(ticket.canTransitionTo(TicketStatus.BACKLOG))
    }

    @Test
    fun `DONE cannot transition to any status`() {
        val ticket = createTicket(status = TicketStatus.DONE)
        TicketStatus.entries.forEach { status ->
            assertFalse(ticket.canTransitionTo(status))
        }
    }

    @Test
    fun `transitionTo throws with proper error message for invalid transition`() {
        val ticket = createTicket(status = TicketStatus.BACKLOG)
        val exception = assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.IN_REVIEW, now)
        }
        assertEquals(exception.message?.contains("BACKLOG"), true)
        assertEquals(exception.message?.contains("IN_REVIEW"), true)
    }

    // =============================================================================
    // ASSIGNMENT TESTS
    // =============================================================================

    @Test
    fun `can assign ticket to agent`() {
        val ticket = createTicket()
        val assigned = ticket.assignTo(assigneeAgentId, now)
        assertEquals(assigneeAgentId, assigned.assignedAgentId)
    }

    @Test
    fun `can unassign ticket`() {
        val ticket = createTicket().assignTo(assigneeAgentId, now)
        val unassigned = ticket.assignTo(null, now)
        assertEquals(null, unassigned.assignedAgentId)
    }

    @Test
    fun `assignment updates updatedAt timestamp`() {
        val ticket = createTicket()
        val laterTime = Clock.System.now()
        val assigned = ticket.assignTo(assigneeAgentId, laterTime)
        assertEquals(laterTime, assigned.updatedAt)
    }

    // =============================================================================
    // HELPER PROPERTY TESTS
    // =============================================================================

    @Test
    fun `isComplete returns true only for DONE status`() {
        assertTrue(createTicket(status = TicketStatus.DONE).isComplete)
        assertFalse(createTicket(status = TicketStatus.BACKLOG).isComplete)
        assertFalse(createTicket(status = TicketStatus.READY).isComplete)
        assertFalse(createTicket(status = TicketStatus.IN_PROGRESS).isComplete)
        assertFalse(createTicket(status = TicketStatus.BLOCKED).isComplete)
        assertFalse(createTicket(status = TicketStatus.IN_REVIEW).isComplete)
    }

    @Test
    fun `isBlocked returns true only for BLOCKED status`() {
        assertTrue(createTicket(status = TicketStatus.BLOCKED).isBlocked)
        assertFalse(createTicket(status = TicketStatus.BACKLOG).isBlocked)
        assertFalse(createTicket(status = TicketStatus.READY).isBlocked)
        assertFalse(createTicket(status = TicketStatus.IN_PROGRESS).isBlocked)
        assertFalse(createTicket(status = TicketStatus.IN_REVIEW).isBlocked)
        assertFalse(createTicket(status = TicketStatus.DONE).isBlocked)
    }

    @Test
    fun `isInProgress returns true only for IN_PROGRESS status`() {
        assertTrue(createTicket(status = TicketStatus.IN_PROGRESS).isInProgress)
        assertFalse(createTicket(status = TicketStatus.BACKLOG).isInProgress)
        assertFalse(createTicket(status = TicketStatus.READY).isInProgress)
        assertFalse(createTicket(status = TicketStatus.BLOCKED).isInProgress)
        assertFalse(createTicket(status = TicketStatus.IN_REVIEW).isInProgress)
        assertFalse(createTicket(status = TicketStatus.DONE).isInProgress)
    }

    @Test
    fun `isReady returns true only for READY status`() {
        assertTrue(createTicket(status = TicketStatus.READY).isReady)
        assertFalse(createTicket(status = TicketStatus.BACKLOG).isReady)
        assertFalse(createTicket(status = TicketStatus.IN_PROGRESS).isReady)
        assertFalse(createTicket(status = TicketStatus.BLOCKED).isReady)
        assertFalse(createTicket(status = TicketStatus.IN_REVIEW).isReady)
        assertFalse(createTicket(status = TicketStatus.DONE).isReady)
    }

    // =============================================================================
    // VALID TRANSITIONS SET TESTS
    // =============================================================================

    @Test
    fun `BACKLOG validTransitions returns correct set`() {
        val transitions = TicketStatus.BACKLOG.validTransitions()
        assertEquals(setOf(TicketStatus.READY, TicketStatus.DONE), transitions)
    }

    @Test
    fun `READY validTransitions returns correct set`() {
        val transitions = TicketStatus.READY.validTransitions()
        assertEquals(setOf(TicketStatus.IN_PROGRESS), transitions)
    }

    @Test
    fun `IN_PROGRESS validTransitions returns correct set`() {
        val transitions = TicketStatus.IN_PROGRESS.validTransitions()
        assertEquals(setOf(TicketStatus.BLOCKED, TicketStatus.IN_REVIEW, TicketStatus.DONE), transitions)
    }

    @Test
    fun `BLOCKED validTransitions returns correct set`() {
        val transitions = TicketStatus.BLOCKED.validTransitions()
        assertEquals(setOf(TicketStatus.IN_PROGRESS), transitions)
    }

    @Test
    fun `IN_REVIEW validTransitions returns correct set`() {
        val transitions = TicketStatus.IN_REVIEW.validTransitions()
        assertEquals(setOf(TicketStatus.IN_PROGRESS, TicketStatus.DONE), transitions)
    }

    @Test
    fun `DONE validTransitions returns empty set`() {
        val transitions = TicketStatus.DONE.validTransitions()
        assertTrue(transitions.isEmpty())
    }
}
