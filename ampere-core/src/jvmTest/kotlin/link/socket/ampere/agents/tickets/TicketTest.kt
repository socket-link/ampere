package link.socket.ampere.agents.tickets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType

class TicketTest {

    private val now = Clock.System.now()
    private val creatorAgentId = "pm-agent-1"
    private val assigneeAgentId = "eng-agent-1"

    private fun createTicket(
        status: TicketStatus = TicketStatus.Backlog,
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
        val ticket = createTicket(status = TicketStatus.Backlog)
        assertEquals(TicketStatus.Backlog, ticket.status)
    }

    @Test
    fun `can create ticket with Ready status`() {
        val ticket = createTicket(status = TicketStatus.Ready)
        assertEquals(TicketStatus.Ready, ticket.status)
    }

    @Test
    fun `can create ticket with InProgress status`() {
        val ticket = createTicket(status = TicketStatus.InProgress)
        assertEquals(TicketStatus.InProgress, ticket.status)
    }

    @Test
    fun `can create ticket with Blocked status`() {
        val ticket = createTicket(status = TicketStatus.Blocked)
        assertEquals(TicketStatus.Blocked, ticket.status)
    }

    @Test
    fun `can create ticket with InReview status`() {
        val ticket = createTicket(status = TicketStatus.InReview)
        assertEquals(TicketStatus.InReview, ticket.status)
    }

    @Test
    fun `can create ticket with Done status`() {
        val ticket = createTicket(status = TicketStatus.Done)
        assertEquals(TicketStatus.Done, ticket.status)
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
    fun `BACKLOG can transition to Ready`() {
        val ticket = createTicket(status = TicketStatus.Backlog)
        assertTrue(ticket.canTransitionTo(TicketStatus.Ready))
        val updated = ticket.transitionTo(TicketStatus.Ready, now)
        assertEquals(TicketStatus.Ready, updated.status)
    }

    @Test
    fun `BACKLOG can transition to Done`() {
        val ticket = createTicket(status = TicketStatus.Backlog)
        assertTrue(ticket.canTransitionTo(TicketStatus.Done))
        val updated = ticket.transitionTo(TicketStatus.Done, now)
        assertEquals(TicketStatus.Done, updated.status)
    }

    @Test
    fun `Ready can transition to InProgress`() {
        val ticket = createTicket(status = TicketStatus.Ready)
        assertTrue(ticket.canTransitionTo(TicketStatus.InProgress))
        val updated = ticket.transitionTo(TicketStatus.InProgress, now)
        assertEquals(TicketStatus.InProgress, updated.status)
    }

    @Test
    fun `InProgress can transition to Blocked`() {
        val ticket = createTicket(status = TicketStatus.InProgress)
        assertTrue(ticket.canTransitionTo(TicketStatus.Blocked))
        val updated = ticket.transitionTo(TicketStatus.Blocked, now)
        assertEquals(TicketStatus.Blocked, updated.status)
    }

    @Test
    fun `InProgress can transition to InReview`() {
        val ticket = createTicket(status = TicketStatus.InProgress)
        assertTrue(ticket.canTransitionTo(TicketStatus.InReview))
        val updated = ticket.transitionTo(TicketStatus.InReview, now)
        assertEquals(TicketStatus.InReview, updated.status)
    }

    @Test
    fun `InProgress can transition to Done`() {
        val ticket = createTicket(status = TicketStatus.InProgress)
        assertTrue(ticket.canTransitionTo(TicketStatus.Done))
        val updated = ticket.transitionTo(TicketStatus.Done, now)
        assertEquals(TicketStatus.Done, updated.status)
    }

    @Test
    fun `Blocked can transition back to InProgress`() {
        val ticket = createTicket(status = TicketStatus.Blocked)
        assertTrue(ticket.canTransitionTo(TicketStatus.InProgress))
        val updated = ticket.transitionTo(TicketStatus.InProgress, now)
        assertEquals(TicketStatus.InProgress, updated.status)
    }

    @Test
    fun `InReview can transition to InProgress`() {
        val ticket = createTicket(status = TicketStatus.InReview)
        assertTrue(ticket.canTransitionTo(TicketStatus.InProgress))
        val updated = ticket.transitionTo(TicketStatus.InProgress, now)
        assertEquals(TicketStatus.InProgress, updated.status)
    }

    @Test
    fun `InReview can transition to Done`() {
        val ticket = createTicket(status = TicketStatus.InReview)
        assertTrue(ticket.canTransitionTo(TicketStatus.Done))
        val updated = ticket.transitionTo(TicketStatus.Done, now)
        assertEquals(TicketStatus.Done, updated.status)
    }

    // =============================================================================
    // INVALID STATE TRANSITION TESTS
    // =============================================================================

    @Test
    fun `BACKLOG cannot transition to InProgress`() {
        val ticket = createTicket(status = TicketStatus.Backlog)
        assertFalse(ticket.canTransitionTo(TicketStatus.InProgress))
    }

    @Test
    fun `BACKLOG cannot transition to InReview`() {
        val ticket = createTicket(status = TicketStatus.Backlog)
        assertFalse(ticket.canTransitionTo(TicketStatus.InReview))
        assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.InReview, now)
        }
    }

    @Test
    fun `BACKLOG cannot transition to Blocked`() {
        val ticket = createTicket(status = TicketStatus.Backlog)
        assertFalse(ticket.canTransitionTo(TicketStatus.Blocked))
        assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.Blocked, now)
        }
    }

    @Test
    fun `Ready cannot transition to Done directly`() {
        val ticket = createTicket(status = TicketStatus.Ready)
        assertFalse(ticket.canTransitionTo(TicketStatus.Done))
        assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.Done, now)
        }
    }

    @Test
    fun `Ready cannot transition to Blocked`() {
        val ticket = createTicket(status = TicketStatus.Ready)
        assertFalse(ticket.canTransitionTo(TicketStatus.Blocked))
    }

    @Test
    fun `Ready cannot transition to InReview`() {
        val ticket = createTicket(status = TicketStatus.Ready)
        assertFalse(ticket.canTransitionTo(TicketStatus.InReview))
    }

    @Test
    fun `Ready cannot transition back to BACKLOG`() {
        val ticket = createTicket(status = TicketStatus.Ready)
        assertFalse(ticket.canTransitionTo(TicketStatus.Backlog))
    }

    @Test
    fun `InProgress cannot transition to BACKLOG`() {
        val ticket = createTicket(status = TicketStatus.InProgress)
        assertFalse(ticket.canTransitionTo(TicketStatus.Backlog))
    }

    @Test
    fun `InProgress cannot transition to Ready`() {
        val ticket = createTicket(status = TicketStatus.InProgress)
        assertFalse(ticket.canTransitionTo(TicketStatus.Ready))
    }

    @Test
    fun `Blocked cannot transition to Done`() {
        val ticket = createTicket(status = TicketStatus.Blocked)
        assertFalse(ticket.canTransitionTo(TicketStatus.Done))
        assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.Done, now)
        }
    }

    @Test
    fun `Blocked cannot transition to InReview`() {
        val ticket = createTicket(status = TicketStatus.Blocked)
        assertFalse(ticket.canTransitionTo(TicketStatus.InReview))
    }

    @Test
    fun `InReview cannot transition to Blocked`() {
        val ticket = createTicket(status = TicketStatus.InReview)
        assertFalse(ticket.canTransitionTo(TicketStatus.Blocked))
    }

    @Test
    fun `InReview cannot transition to Ready`() {
        val ticket = createTicket(status = TicketStatus.InReview)
        assertFalse(ticket.canTransitionTo(TicketStatus.Ready))
    }

    @Test
    fun `InReview cannot transition to BACKLOG`() {
        val ticket = createTicket(status = TicketStatus.InReview)
        assertFalse(ticket.canTransitionTo(TicketStatus.Backlog))
    }

    @Test
    fun `Done cannot transition to any status`() {
        val ticket = createTicket(status = TicketStatus.Done)
        TicketStatus.values.forEach { status ->
            assertFalse(ticket.canTransitionTo(status))
        }
    }

    @Test
    fun `transitionTo throws with proper error message for invalid transition`() {
        val ticket = createTicket(status = TicketStatus.Backlog)
        val exception = assertFailsWith<IllegalArgumentException> {
            ticket.transitionTo(TicketStatus.InReview, now)
        }
        assertEquals(exception.message?.contains("Backlog"), true)
        assertEquals(exception.message?.contains("InReview"), true)
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
    fun `isComplete returns true only for Done status`() {
        assertTrue(createTicket(status = TicketStatus.Done).isComplete)
        assertFalse(createTicket(status = TicketStatus.Backlog).isComplete)
        assertFalse(createTicket(status = TicketStatus.Ready).isComplete)
        assertFalse(createTicket(status = TicketStatus.InProgress).isComplete)
        assertFalse(createTicket(status = TicketStatus.Blocked).isComplete)
        assertFalse(createTicket(status = TicketStatus.InReview).isComplete)
    }

    @Test
    fun `isBlocked returns true only for Blocked status`() {
        assertTrue(createTicket(status = TicketStatus.Blocked).isBlocked)
        assertFalse(createTicket(status = TicketStatus.Backlog).isBlocked)
        assertFalse(createTicket(status = TicketStatus.Ready).isBlocked)
        assertFalse(createTicket(status = TicketStatus.InProgress).isBlocked)
        assertFalse(createTicket(status = TicketStatus.InReview).isBlocked)
        assertFalse(createTicket(status = TicketStatus.Done).isBlocked)
    }

    @Test
    fun `isInProgress returns true only for InProgress status`() {
        assertTrue(createTicket(status = TicketStatus.InProgress).isInProgress)
        assertFalse(createTicket(status = TicketStatus.Backlog).isInProgress)
        assertFalse(createTicket(status = TicketStatus.Ready).isInProgress)
        assertFalse(createTicket(status = TicketStatus.Blocked).isInProgress)
        assertFalse(createTicket(status = TicketStatus.InReview).isInProgress)
        assertFalse(createTicket(status = TicketStatus.Done).isInProgress)
    }

    @Test
    fun `isReady returns true only for Ready status`() {
        assertTrue(createTicket(status = TicketStatus.Ready).isReadyAndNotStarted)
        assertFalse(createTicket(status = TicketStatus.Backlog).isReadyAndNotStarted)
        assertFalse(createTicket(status = TicketStatus.InProgress).isReadyAndNotStarted)
        assertFalse(createTicket(status = TicketStatus.Blocked).isReadyAndNotStarted)
        assertFalse(createTicket(status = TicketStatus.InReview).isReadyAndNotStarted)
        assertFalse(createTicket(status = TicketStatus.Done).isReadyAndNotStarted)
    }

    // =============================================================================
    // VALID TRANSITIONS SET TESTS
    // =============================================================================

    @Test
    fun `BACKLOG validTransitions returns correct set`() {
        val transitions = TicketStatus.Backlog.validTransitions()
        assertEquals(setOf(TicketStatus.Ready, TicketStatus.Done), transitions)
    }

    @Test
    fun `Ready validTransitions returns correct set`() {
        val transitions = TicketStatus.Ready.validTransitions()
        assertEquals(setOf(TicketStatus.InProgress), transitions)
    }

    @Test
    fun `InProgress validTransitions returns correct set`() {
        val transitions = TicketStatus.InProgress.validTransitions()
        assertEquals(setOf(TicketStatus.Blocked, TicketStatus.InReview, TicketStatus.Done), transitions)
    }

    @Test
    fun `Blocked validTransitions returns correct set`() {
        val transitions = TicketStatus.Blocked.validTransitions()
        assertEquals(setOf(TicketStatus.InProgress), transitions)
    }

    @Test
    fun `InReview validTransitions returns correct set`() {
        val transitions = TicketStatus.InReview.validTransitions()
        assertEquals(setOf(TicketStatus.InProgress, TicketStatus.Done), transitions)
    }

    @Test
    fun `Done validTransitions returns empty set`() {
        val transitions = TicketStatus.Done.validTransitions()
        assertTrue(transitions.isEmpty())
    }
}
