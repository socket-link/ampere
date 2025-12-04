package link.socket.ampere.agents.events.tickets

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.db.Database

class TicketViewServiceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var ticketRepository: TicketRepository
    private lateinit var ticketViewService: TicketViewService

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database.Companion(driver)
        ticketRepository = TicketRepository(database)
        ticketViewService = DefaultTicketViewService(ticketRepository)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Helper Methods ====================

    // Truncate to milliseconds to match database precision
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
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
    // LIST ACTIVE TICKETS TESTS
    // =============================================================================

    @Test
    fun `listActiveTickets returns all tickets that are not Done`() {
        runBlocking {
            // Create tickets in various statuses
            ticketRepository.createTicket(createTicket(id = "t1", status = TicketStatus.Backlog))
            ticketRepository.createTicket(createTicket(id = "t2", status = TicketStatus.Ready))
            ticketRepository.createTicket(createTicket(id = "t3", status = TicketStatus.InProgress))
            ticketRepository.createTicket(createTicket(id = "t4", status = TicketStatus.Done))

            val result = ticketViewService.listActiveTickets()

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!
            assertEquals(3, tickets.size)
            assertFalse(tickets.any { it.status == "Done" })
        }
    }

    @Test
    fun `listActiveTickets returns empty list when no active tickets exist`() {
        runBlocking {
            // Create only completed tickets
            ticketRepository.createTicket(createTicket(id = "t1", status = TicketStatus.Done))
            ticketRepository.createTicket(createTicket(id = "t2", status = TicketStatus.Done))

            val result = ticketViewService.listActiveTickets()

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!
            assertEquals(0, tickets.size)
        }
    }

    @Test
    fun `listActiveTickets returns empty list when no tickets exist`() {
        runBlocking {
            val result = ticketViewService.listActiveTickets()

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!
            assertEquals(0, tickets.size)
        }
    }

    @Test
    fun `listActiveTickets sorts by priority descending then createdAt ascending`() {
        runBlocking {
            val baseTime = Clock.System.now()
            val older = baseTime
            val newer = baseTime + kotlin.time.Duration.parse("1h")

            // Create tickets with different priorities and timestamps
            ticketRepository.createTicket(
                createTicket(id = "low-new", priority = TicketPriority.LOW, createdAt = newer)
            )
            ticketRepository.createTicket(
                createTicket(id = "high-old", priority = TicketPriority.HIGH, createdAt = older)
            )
            ticketRepository.createTicket(
                createTicket(id = "high-new", priority = TicketPriority.HIGH, createdAt = newer)
            )
            ticketRepository.createTicket(
                createTicket(id = "medium-old", priority = TicketPriority.MEDIUM, createdAt = older)
            )

            val result = ticketViewService.listActiveTickets()
            val tickets = result.getOrNull()!!

            // HIGH priority should come first
            assertEquals("HIGH", tickets[0].priority)
            assertEquals("HIGH", tickets[1].priority)
            // Within HIGH priority, older should come before newer
            assertEquals("high-old", tickets[0].ticketId)
            assertEquals("high-new", tickets[1].ticketId)
            // MEDIUM should come after HIGH
            assertEquals("MEDIUM", tickets[2].priority)
            // LOW should come last
            assertEquals("LOW", tickets[3].priority)
        }
    }

    @Test
    fun `listActiveTickets includes correct summary information`() {
        runBlocking {
            val ticket = createTicket(
                id = "ticket-123",
                title = "Test Ticket",
                status = TicketStatus.InProgress,
                assignedAgentId = assigneeAgentId,
                priority = TicketPriority.HIGH,
                createdAt = now,
            )
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.listActiveTickets()

            assertTrue(result.isSuccess)
            val summaries = result.getOrNull()!!
            assertEquals(1, summaries.size)

            val summary = summaries[0]
            assertEquals("ticket-123", summary.ticketId)
            assertEquals("Test Ticket", summary.title)
            assertEquals("In Progress", summary.status)
            assertEquals(assigneeAgentId, summary.assigneeId)
            assertEquals("HIGH", summary.priority)
            assertEquals(now, summary.createdAt)
        }
    }

    @Test
    fun `listActiveTickets includes unassigned tickets`() {
        runBlocking {
            ticketRepository.createTicket(
                createTicket(id = "unassigned", assignedAgentId = null, status = TicketStatus.Ready)
            )

            val result = ticketViewService.listActiveTickets()

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!
            assertEquals(1, tickets.size)
            assertEquals(null, tickets[0].assigneeId)
        }
    }

    // =============================================================================
    // GET TICKET DETAIL TESTS
    // =============================================================================

    @Test
    fun `getTicketDetail returns complete ticket information`() {
        runBlocking {
            val dueDate = now + kotlin.time.Duration.parse("7d")
            val ticket = createTicket(
                id = "ticket-detail",
                title = "Detailed Ticket",
                description = "A comprehensive description of the ticket",
                type = TicketType.BUG,
                priority = TicketPriority.CRITICAL,
                status = TicketStatus.InProgress,
                assignedAgentId = assigneeAgentId,
                createdByAgentId = creatorAgentId,
                dueDate = dueDate,
            )
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.getTicketDetail("ticket-detail")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()!!

            assertEquals("ticket-detail", detail.ticketId)
            assertEquals("Detailed Ticket", detail.title)
            assertEquals("A comprehensive description of the ticket", detail.description)
            assertEquals("In Progress", detail.status)
            assertEquals(assigneeAgentId, detail.assigneeId)
            assertEquals("CRITICAL", detail.priority)
            assertEquals("BUG", detail.type)
            assertEquals(dueDate, detail.dueDate)
            assertEquals(creatorAgentId, detail.createdByAgentId)
        }
    }

    @Test
    fun `getTicketDetail extracts acceptance criteria from description with explicit section`() {
        runBlocking {
            val description = """
                This ticket implements a new feature.

                Acceptance Criteria:
                - User can login
                - User can logout
                - Password must be validated

                Additional Notes:
                Some other information
            """.trimIndent()

            val ticket = createTicket(
                id = "ticket-ac",
                description = description,
            )
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.getTicketDetail("ticket-ac")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()!!

            assertEquals(3, detail.acceptanceCriteria.size)
            assertEquals("User can login", detail.acceptanceCriteria[0])
            assertEquals("User can logout", detail.acceptanceCriteria[1])
            assertEquals("Password must be validated", detail.acceptanceCriteria[2])
        }
    }

    @Test
    fun `getTicketDetail extracts acceptance criteria with bullet points`() {
        runBlocking {
            val description = """
                Fix the login bug

                * Validate email format
                * Check password strength
                * Handle network errors
            """.trimIndent()

            val ticket = createTicket(id = "ticket-bullets", description = description)
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.getTicketDetail("ticket-bullets")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()!!

            assertEquals(3, detail.acceptanceCriteria.size)
            assertEquals("Validate email format", detail.acceptanceCriteria[0])
            assertEquals("Check password strength", detail.acceptanceCriteria[1])
            assertEquals("Handle network errors", detail.acceptanceCriteria[2])
        }
    }

    @Test
    fun `getTicketDetail extracts acceptance criteria with numbered list`() {
        runBlocking {
            val description = """
                Implement user registration

                1. Create registration form
                2. Add email validation
                3. Integrate with backend API
                4. Show success message
            """.trimIndent()

            val ticket = createTicket(id = "ticket-numbered", description = description)
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.getTicketDetail("ticket-numbered")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()!!

            assertEquals(4, detail.acceptanceCriteria.size)
            assertEquals("Create registration form", detail.acceptanceCriteria[0])
            assertEquals("Add email validation", detail.acceptanceCriteria[1])
            assertEquals("Integrate with backend API", detail.acceptanceCriteria[2])
            assertEquals("Show success message", detail.acceptanceCriteria[3])
        }
    }

    @Test
    fun `getTicketDetail extracts acceptance criteria with checkboxes`() {
        runBlocking {
            val description = """
                Task list:

                - [ ] Incomplete task 1
                - [x] Complete task 1
                - [ ] Incomplete task 2
            """.trimIndent()

            val ticket = createTicket(id = "ticket-checkboxes", description = description)
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.getTicketDetail("ticket-checkboxes")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()!!

            assertEquals(3, detail.acceptanceCriteria.size)
            assertEquals("Incomplete task 1", detail.acceptanceCriteria[0])
            assertEquals("Complete task 1", detail.acceptanceCriteria[1])
            assertEquals("Incomplete task 2", detail.acceptanceCriteria[2])
        }
    }

    @Test
    fun `getTicketDetail returns empty acceptance criteria when description has no list items`() {
        runBlocking {
            val description = "This is a simple description with no bullet points or lists."

            val ticket = createTicket(id = "ticket-no-ac", description = description)
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.getTicketDetail("ticket-no-ac")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()!!

            assertEquals(0, detail.acceptanceCriteria.size)
        }
    }

    @Test
    fun `getTicketDetail handles tickets with null assignee`() {
        runBlocking {
            val ticket = createTicket(
                id = "unassigned-detail",
                assignedAgentId = null,
            )
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.getTicketDetail("unassigned-detail")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()!!
            assertEquals(null, detail.assigneeId)
        }
    }

    @Test
    fun `getTicketDetail handles tickets with null dueDate`() {
        runBlocking {
            val ticket = createTicket(
                id = "no-due-date",
                dueDate = null,
            )
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.getTicketDetail("no-due-date")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()!!
            assertEquals(null, detail.dueDate)
        }
    }

    @Test
    fun `getTicketDetail returns failure for nonexistent ticket`() {
        runBlocking {
            val result = ticketViewService.getTicketDetail("nonexistent-ticket")

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertIs<TicketError.TicketNotFound>(error)
            assertEquals("nonexistent-ticket", error.ticketId)
        }
    }

    @Test
    fun `getTicketDetail sets relatedThreadId to null`() {
        runBlocking {
            val ticket = createTicket(id = "ticket-thread")
            ticketRepository.createTicket(ticket)

            val result = ticketViewService.getTicketDetail("ticket-thread")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()!!
            assertEquals(null, detail.relatedThreadId)
        }
    }

    // =============================================================================
    // INTEGRATION TESTS
    // =============================================================================

    @Test
    fun `service correctly filters active tickets from mixed statuses`() {
        runBlocking {
            // Create a realistic scenario with tickets in various states
            ticketRepository.createTicket(createTicket(id = "backlog-1", status = TicketStatus.Backlog))
            ticketRepository.createTicket(createTicket(id = "ready-1", status = TicketStatus.Ready))
            ticketRepository.createTicket(createTicket(id = "progress-1", status = TicketStatus.InProgress))
            ticketRepository.createTicket(createTicket(id = "blocked-1", status = TicketStatus.Blocked))
            ticketRepository.createTicket(createTicket(id = "review-1", status = TicketStatus.InReview))
            ticketRepository.createTicket(createTicket(id = "done-1", status = TicketStatus.Done))
            ticketRepository.createTicket(createTicket(id = "done-2", status = TicketStatus.Done))

            val result = ticketViewService.listActiveTickets()

            assertTrue(result.isSuccess)
            val tickets = result.getOrNull()!!

            // Should exclude only the Done tickets
            assertEquals(5, tickets.size)

            val statuses = tickets.map { it.status }.toSet()
            assertTrue("Backlog" in statuses)
            assertTrue("Ready" in statuses)
            assertTrue("In Progress" in statuses)
            assertTrue("Blocked" in statuses)
            assertTrue("In Review" in statuses)
            assertFalse("Done" in statuses)
        }
    }
}
