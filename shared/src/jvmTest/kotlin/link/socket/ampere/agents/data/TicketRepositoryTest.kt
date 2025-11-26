package link.socket.ampere.agents.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.core.status.TicketStatus
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketError
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketRepository
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.db.Database

class TicketRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: TicketRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database.Companion(driver)
        repo = TicketRepository(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Helper Methods ====================

    private val now = Clock.System.now()
    private val creatorAgentId = "pm-agent-1"
    private val assigneeAgentId = "eng-agent-1"

    private fun createTicket(
        id: String = "ticket-1",
        title: String = "Test Ticket",
        description: String = "Test Description",
        type: TicketType = TicketType.FEATURE,
        priority: TicketPriority = TicketPriority.MEDIUM,
        status: TicketStatus = TicketStatus.Backlog,
        assignedAgentId: String? = null,
        createdByAgentId: String = creatorAgentId,
        createdAt: Instant = now,
        updatedAt: Instant = now,
        dueDate: Instant? = null,
    ): Ticket = Ticket(
        id = id,
        title = title,
        description = description,
        type = type,
        priority = priority,
        status = status,
        assignedAgentId = assignedAgentId,
        createdByAgentId = createdByAgentId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        dueDate = dueDate,
    )

    // =============================================================================
    // CREATE TICKET TESTS
    // =============================================================================

    @Test
    fun `createTicket successfully inserts and returns ticket`() {
        runBlocking {
            val ticket = createTicket()

            val result = repo.createTicket(ticket)

            assertTrue(result.isSuccess)
            assertEquals(ticket, result.getOrNull())

            // Verify it was actually persisted
            val retrieved = repo.getTicket(ticket.id).getOrNull()
            assertNotNull(retrieved)
            assertEquals(ticket.id, retrieved.id)
            assertEquals(ticket.title, retrieved.title)
            assertEquals(ticket.description, retrieved.description)
            assertEquals(ticket.type, retrieved.type)
            assertEquals(ticket.priority, retrieved.priority)
            assertEquals(ticket.status, retrieved.status)
        }
    }

    @Test
    fun `createTicket preserves all ticket fields`() {
        runBlocking {
            val dueDate = Clock.System.now()
            val ticket = createTicket(
                id = "ticket-full",
                title = "Full Ticket",
                description = "Complete description",
                type = TicketType.BUG,
                priority = TicketPriority.CRITICAL,
                status = TicketStatus.Ready,
                assignedAgentId = assigneeAgentId,
                dueDate = dueDate,
            )

            repo.createTicket(ticket)

            val retrieved = repo.getTicket(ticket.id).getOrNull()
            assertNotNull(retrieved)
            assertEquals(ticket.title, retrieved.title)
            assertEquals(ticket.description, retrieved.description)
            assertEquals(TicketType.BUG, retrieved.type)
            assertEquals(TicketPriority.CRITICAL, retrieved.priority)
            assertEquals(TicketStatus.Ready, retrieved.status)
            assertEquals(assigneeAgentId, retrieved.assignedAgentId)
            assertNotNull(retrieved.dueDate)
        }
    }

    @Test
    fun `createTicket returns error for duplicate id`() {
        runBlocking {
            val ticket = createTicket()
            repo.createTicket(ticket)

            val duplicate = createTicket(id = ticket.id, title = "Duplicate")
            val result = repo.createTicket(duplicate)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.DatabaseError>(error)
        }
    }

    // =============================================================================
    // GET TICKET TESTS
    // =============================================================================

    @Test
    fun `getTicket returns null for non-existent id without throwing`() {
        runBlocking {
            val result = repo.getTicket("nonexistent-id")

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
        }
    }

    @Test
    fun `getTicket returns ticket for existing id`() {
        runBlocking {
            val ticket = createTicket()
            repo.createTicket(ticket)

            val result = repo.getTicket(ticket.id)

            assertTrue(result.isSuccess)
            assertNotNull(result.getOrNull())
            assertEquals(ticket.id, result.getOrNull()?.id)
        }
    }

    // =============================================================================
    // UPDATE STATUS TESTS
    // =============================================================================

    @Test
    fun `updateStatus accepts valid transition BACKLOG to READY`() {
        runBlocking {
            val ticket = createTicket(status = TicketStatus.Backlog)
            repo.createTicket(ticket)

            val result = repo.updateStatus(ticket.id, TicketStatus.Ready)

            assertTrue(result.isSuccess)

            val updated = repo.getTicket(ticket.id).getOrNull()
            assertNotNull(updated)
            assertEquals(TicketStatus.Ready, updated.status)
        }
    }

    @Test
    fun `updateStatus accepts valid transition READY to IN_PROGRESS`() {
        runBlocking {
            val ticket = createTicket(status = TicketStatus.Ready)
            repo.createTicket(ticket)

            val result = repo.updateStatus(ticket.id, TicketStatus.InProgress)

            assertTrue(result.isSuccess)
            val updated = repo.getTicket(ticket.id).getOrNull()
            assertEquals(TicketStatus.InProgress, updated?.status)
        }
    }

    @Test
    fun `updateStatus accepts valid transition IN_PROGRESS to DONE`() {
        runBlocking {
            val ticket = createTicket(status = TicketStatus.InProgress)
            repo.createTicket(ticket)

            val result = repo.updateStatus(ticket.id, TicketStatus.Done)

            assertTrue(result.isSuccess)
            val updated = repo.getTicket(ticket.id).getOrNull()
            assertEquals(TicketStatus.Done, updated?.status)
        }
    }

    @Test
    fun `updateStatus rejects invalid transition BACKLOG to IN_PROGRESS`() {
        runBlocking {
            val ticket = createTicket(status = TicketStatus.Backlog)
            repo.createTicket(ticket)

            val result = repo.updateStatus(ticket.id, TicketStatus.InProgress)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.InvalidStateTransition>(error)
            assertEquals(TicketStatus.Backlog, error.fromState)
            assertEquals(TicketStatus.InProgress, error.toState)
        }
    }

    @Test
    fun `updateStatus rejects invalid transition READY to DONE`() {
        runBlocking {
            val ticket = createTicket(status = TicketStatus.Ready)
            repo.createTicket(ticket)

            val result = repo.updateStatus(ticket.id, TicketStatus.Done)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.InvalidStateTransition>(error)
            assertEquals(TicketStatus.Ready, error.fromState)
            assertEquals(TicketStatus.Done, error.toState)
        }
    }

    @Test
    fun `updateStatus rejects invalid transition DONE to any status`() {
        runBlocking {
            val ticket = createTicket(status = TicketStatus.Done)
            repo.createTicket(ticket)

            val result = repo.updateStatus(ticket.id, TicketStatus.InProgress)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.InvalidStateTransition>(error)
        }
    }

    @Test
    fun `updateStatus returns TicketNotFound for nonexistent ticket`() {
        runBlocking {
            val result = repo.updateStatus("nonexistent", TicketStatus.Ready)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.TicketNotFound>(error)
            assertEquals("nonexistent", error.ticketId)
        }
    }

    @Test
    fun `updateStatus updates the updatedAt timestamp`() {
        runBlocking {
            val ticket = createTicket(status = TicketStatus.Backlog)
            repo.createTicket(ticket)

            // Small delay to ensure different timestamp
            kotlinx.coroutines.delay(10)

            repo.updateStatus(ticket.id, TicketStatus.Ready)

            val updated = repo.getTicket(ticket.id).getOrNull()
            assertNotNull(updated)
            assertTrue(updated.updatedAt > ticket.updatedAt)
        }
    }

    // =============================================================================
    // ASSIGN TICKET TESTS
    // =============================================================================

    @Test
    fun `assignTicket assigns agent to ticket`() {
        runBlocking {
            val ticket = createTicket()
            repo.createTicket(ticket)

            val result = repo.assignTicket(ticket.id, assigneeAgentId)

            assertTrue(result.isSuccess)
            val updated = repo.getTicket(ticket.id).getOrNull()
            assertEquals(assigneeAgentId, updated?.assignedAgentId)
        }
    }

    @Test
    fun `assignTicket can unassign ticket with null`() {
        runBlocking {
            val ticket = createTicket(assignedAgentId = assigneeAgentId)
            repo.createTicket(ticket)

            val result = repo.assignTicket(ticket.id, null)

            assertTrue(result.isSuccess)
            val updated = repo.getTicket(ticket.id).getOrNull()
            assertNull(updated?.assignedAgentId)
        }
    }

    @Test
    fun `assignTicket returns TicketNotFound for nonexistent ticket`() {
        runBlocking {
            val result = repo.assignTicket("nonexistent", assigneeAgentId)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.TicketNotFound>(error)
        }
    }

    // =============================================================================
    // GET TICKETS BY STATUS TESTS
    // =============================================================================

    @Test
    fun `getTicketsByStatus returns tickets with matching status`() {
        runBlocking {
            repo.createTicket(createTicket(id = "t1", status = TicketStatus.Backlog))
            repo.createTicket(createTicket(id = "t2", status = TicketStatus.Backlog))
            repo.createTicket(createTicket(id = "t3", status = TicketStatus.Ready))

            val result = repo.getTicketsByStatus(TicketStatus.Backlog)

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!
            assertEquals(2, tickets.size)
            assertTrue(tickets.all { it.status == TicketStatus.Backlog })
        }
    }

    @Test
    fun `getTicketsByStatus returns empty list when no matches`() {
        runBlocking {
            repo.createTicket(createTicket(status = TicketStatus.Backlog))

            val result = repo.getTicketsByStatus(TicketStatus.Done)

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrNull()?.size)
        }
    }

    @Test
    fun `getTicketsByStatus orders by priority DESC then createdAt ASC`() {
        runBlocking {
            val baseTime = Clock.System.now()
            val older = baseTime
            val newer = baseTime + kotlin.time.Duration.parse("1h")

            repo.createTicket(createTicket(id = "low-old", priority = TicketPriority.LOW, createdAt = older))
            repo.createTicket(createTicket(id = "high-new", priority = TicketPriority.HIGH, createdAt = newer))
            repo.createTicket(createTicket(id = "high-old", priority = TicketPriority.HIGH, createdAt = older))

            val result = repo.getTicketsByStatus(TicketStatus.Backlog)
            val tickets = result.getOrNull()!!

            // HIGH priority should come first
            assertEquals(TicketPriority.HIGH, tickets[0].priority)
            assertEquals(TicketPriority.HIGH, tickets[1].priority)
            assertEquals(TicketPriority.LOW, tickets[2].priority)
        }
    }

    // =============================================================================
    // GET TICKETS BY AGENT TESTS
    // =============================================================================

    @Test
    fun `getTicketsByAgent returns tickets assigned to agent`() {
        runBlocking {
            repo.createTicket(createTicket(id = "t1", assignedAgentId = "agent-1"))
            repo.createTicket(createTicket(id = "t2", assignedAgentId = "agent-1"))
            repo.createTicket(createTicket(id = "t3", assignedAgentId = "agent-2"))

            val result = repo.getTicketsByAgent("agent-1")

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!
            assertEquals(2, tickets.size)
            assertTrue(tickets.all { it.assignedAgentId == "agent-1" })
        }
    }

    @Test
    fun `getTicketsByAgent returns empty list for unassigned agent`() {
        runBlocking {
            repo.createTicket(createTicket(assignedAgentId = "agent-1"))

            val result = repo.getTicketsByAgent("agent-unknown")

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrNull()?.size)
        }
    }

    // =============================================================================
    // GET ALL TICKETS TESTS
    // =============================================================================

    @Test
    fun `getAllTickets returns all tickets`() {
        runBlocking {
            repo.createTicket(createTicket(id = "t1"))
            repo.createTicket(createTicket(id = "t2"))
            repo.createTicket(createTicket(id = "t3"))

            val result = repo.getAllTickets()

            assertTrue(result.isSuccess)
            assertEquals(3, result.getOrNull()?.size)
        }
    }

    @Test
    fun `getAllTickets returns empty list when no tickets exist`() {
        runBlocking {
            val result = repo.getAllTickets()

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrNull()?.size)
        }
    }

    // =============================================================================
    // GET TICKETS BY PRIORITY TESTS
    // =============================================================================

    @Test
    fun `getTicketsByPriority returns tickets with matching priority`() {
        runBlocking {
            repo.createTicket(createTicket(id = "t1", priority = TicketPriority.CRITICAL))
            repo.createTicket(createTicket(id = "t2", priority = TicketPriority.CRITICAL))
            repo.createTicket(createTicket(id = "t3", priority = TicketPriority.LOW))

            val result = repo.getTicketsByPriority(TicketPriority.CRITICAL)

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!
            assertEquals(2, tickets.size)
            assertTrue(tickets.all { it.priority == TicketPriority.CRITICAL })
        }
    }

    // =============================================================================
    // GET TICKETS BY TYPE TESTS
    // =============================================================================

    @Test
    fun `getTicketsByType returns tickets with matching type`() {
        runBlocking {
            repo.createTicket(createTicket(id = "t1", type = TicketType.BUG))
            repo.createTicket(createTicket(id = "t2", type = TicketType.BUG))
            repo.createTicket(createTicket(id = "t3", type = TicketType.FEATURE))

            val result = repo.getTicketsByType(TicketType.BUG)

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!
            assertEquals(2, tickets.size)
            assertTrue(tickets.all { it.type == TicketType.BUG })
        }
    }

    // =============================================================================
    // GET TICKETS BY CREATOR TESTS
    // =============================================================================

    @Test
    fun `getTicketsByCreator returns tickets created by agent`() {
        runBlocking {
            repo.createTicket(createTicket(id = "t1", createdByAgentId = "creator-1"))
            repo.createTicket(createTicket(id = "t2", createdByAgentId = "creator-1"))
            repo.createTicket(createTicket(id = "t3", createdByAgentId = "creator-2"))

            val result = repo.getTicketsByCreator("creator-1")

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!
            assertEquals(2, tickets.size)
            assertTrue(tickets.all { it.createdByAgentId == "creator-1" })
        }
    }

    // =============================================================================
    // UPDATE TICKET DETAILS TESTS
    // =============================================================================

    @Test
    fun `updateTicketDetails updates specified fields only`() {
        runBlocking {
            val ticket = createTicket(
                title = "Original Title",
                description = "Original Description",
                priority = TicketPriority.LOW,
            )
            repo.createTicket(ticket)

            val result = repo.updateTicketDetails(
                ticketId = ticket.id,
                title = "New Title",
                priority = TicketPriority.HIGH,
            )

            assertTrue(result.isSuccess)
            val updated = result.getOrNull()!!
            assertEquals("New Title", updated.title)
            assertEquals("Original Description", updated.description) // unchanged
            assertEquals(TicketPriority.HIGH, updated.priority)
        }
    }

    @Test
    fun `updateTicketDetails returns TicketNotFound for nonexistent ticket`() {
        runBlocking {
            val result = repo.updateTicketDetails(
                ticketId = "nonexistent",
                title = "New Title",
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.TicketNotFound>(error)
        }
    }

    // =============================================================================
    // DELETE TICKET TESTS
    // =============================================================================

    @Test
    fun `deleteTicket removes ticket from database`() {
        runBlocking {
            val ticket = createTicket()
            repo.createTicket(ticket)

            val result = repo.deleteTicket(ticket.id)

            assertTrue(result.isSuccess)
            assertNull(repo.getTicket(ticket.id).getOrNull())
        }
    }

    @Test
    fun `deleteTicket succeeds even for nonexistent ticket`() {
        runBlocking {
            val result = repo.deleteTicket("nonexistent")

            assertTrue(result.isSuccess)
        }
    }

    // =============================================================================
    // ERROR MESSAGE TESTS
    // =============================================================================

    @Test
    fun `InvalidStateTransition error message contains states`() {
        runBlocking {
            val ticket = createTicket(status = TicketStatus.Backlog)
            repo.createTicket(ticket)

            val result = repo.updateStatus(ticket.id, TicketStatus.InReview)

            val error = result.exceptionOrNull() as TicketError.InvalidStateTransition
            assertTrue(error.message.contains("Backlog"))
            assertTrue(error.message.contains("InReview"))
        }
    }

    @Test
    fun `TicketNotFound error message contains ticketId`() {
        val error = TicketError.TicketNotFound("test-123")
        assertTrue(error.message.contains("test-123"))
    }
}
