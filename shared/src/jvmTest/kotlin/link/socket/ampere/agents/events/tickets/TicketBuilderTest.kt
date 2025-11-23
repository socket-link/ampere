package link.socket.ampere.agents.events.tickets

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.events.bus.EventBus
import link.socket.ampere.agents.events.meetings.MeetingOrchestrator
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.meetings.MeetingSchedulingService
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

class TicketBuilderTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    private lateinit var messageRepository: MessageRepository
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var ticketRepository: TicketRepository

    private lateinit var eventBus: EventBus
    private lateinit var messageApi: AgentMessageApi
    private lateinit var meetingOrchestrator: MeetingOrchestrator
    private lateinit var ticketOrchestrator: TicketOrchestrator

    private val testScope = CoroutineScope(Dispatchers.Default)
    private val stubOrchestratorAgentId: AgentId = "orchestrator-agent"
    private val stubPmAgentId: AgentId = "pm-agent"
    private val stubDevAgentId: AgentId = "dev-agent"

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database.Companion(driver)

        messageRepository = MessageRepository(DEFAULT_JSON, testScope, database)
        meetingRepository = MeetingRepository(DEFAULT_JSON, testScope, database)
        ticketRepository = TicketRepository(database)

        eventBus = EventBus(testScope)
        messageApi = AgentMessageApi(stubOrchestratorAgentId, messageRepository, eventBus)

        meetingOrchestrator = MeetingOrchestrator(
            repository = meetingRepository,
            eventBus = eventBus,
            messageApi = messageApi,
        )

        // Create a MeetingSchedulingService that delegates to the meetingOrchestrator
        val meetingSchedulingService = MeetingSchedulingService { meeting, scheduledBy ->
            meetingOrchestrator.scheduleMeeting(meeting, scheduledBy)
        }

        ticketOrchestrator = TicketOrchestrator(
            ticketRepository = ticketRepository,
            eventBus = eventBus,
            messageApi = messageApi,
            meetingSchedulingService = meetingSchedulingService,
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Fluent API Usage Tests ====================

    @Test
    fun `fluent API creates FEATURE ticket with correct properties`() {
        val spec = TicketBuilder()
            .withTitle("Add user authentication")
            .withDescription("Implement OAuth2 login flow with Google and GitHub providers")
            .ofType(TicketType.FEATURE)
            .withPriority(TicketPriority.HIGH)
            .createdBy(stubPmAgentId)
            .build()

        assertEquals("Add user authentication", spec.title)
        assertEquals("Implement OAuth2 login flow with Google and GitHub providers", spec.description)
        assertEquals(TicketType.FEATURE, spec.type)
        assertEquals(TicketPriority.HIGH, spec.priority)
        assertEquals(stubPmAgentId, spec.createdByAgentId)
        assertNull(spec.assignedToAgentId)
        assertNull(spec.dueDate)
    }

    @Test
    fun `fluent API creates BUG ticket with correct properties`() {
        val spec = TicketBuilder()
            .withTitle("Fix login timeout")
            .withDescription("Users experience 30 second timeout when logging in")
            .ofType(TicketType.BUG)
            .withPriority(TicketPriority.CRITICAL)
            .createdBy(stubPmAgentId)
            .build()

        assertEquals("Fix login timeout", spec.title)
        assertEquals(TicketType.BUG, spec.type)
        assertEquals(TicketPriority.CRITICAL, spec.priority)
    }

    @Test
    fun `fluent API creates TASK ticket with correct properties`() {
        val spec = TicketBuilder()
            .withTitle("Update dependencies")
            .withDescription("Update all npm packages to latest versions")
            .ofType(TicketType.TASK)
            .withPriority(TicketPriority.LOW)
            .createdBy(stubPmAgentId)
            .build()

        assertEquals(TicketType.TASK, spec.type)
        assertEquals(TicketPriority.LOW, spec.priority)
    }

    @Test
    fun `fluent API creates SPIKE ticket with correct properties`() {
        val spec = TicketBuilder()
            .withTitle("Research caching solutions")
            .withDescription("Evaluate Redis vs Memcached for session storage")
            .ofType(TicketType.SPIKE)
            .withPriority(TicketPriority.MEDIUM)
            .createdBy(stubPmAgentId)
            .build()

        assertEquals(TicketType.SPIKE, spec.type)
        assertEquals(TicketPriority.MEDIUM, spec.priority)
    }

    // ==================== Builder Validation Tests ====================

    @Test
    fun `build throws IllegalStateException when title is missing`() {
        val builder = TicketBuilder()
            .withDescription("Test description")
            .ofType(TicketType.TASK)
            .withPriority(TicketPriority.HIGH)
            .createdBy(stubPmAgentId)

        val exception = assertFailsWith<IllegalStateException> {
            builder.build()
        }
        assertTrue(exception.message!!.contains("title"))
    }

    @Test
    fun `build throws IllegalStateException when description is missing`() {
        val builder = TicketBuilder()
            .withTitle("Test title")
            .ofType(TicketType.TASK)
            .withPriority(TicketPriority.HIGH)
            .createdBy(stubPmAgentId)

        val exception = assertFailsWith<IllegalStateException> {
            builder.build()
        }
        assertTrue(exception.message!!.contains("description"))
    }

    @Test
    fun `build throws IllegalStateException when type is missing`() {
        val builder = TicketBuilder()
            .withTitle("Test title")
            .withDescription("Test description")
            .withPriority(TicketPriority.HIGH)
            .createdBy(stubPmAgentId)

        val exception = assertFailsWith<IllegalStateException> {
            builder.build()
        }
        assertTrue(exception.message!!.contains("type"))
    }

    @Test
    fun `build throws IllegalStateException when priority is missing`() {
        val builder = TicketBuilder()
            .withTitle("Test title")
            .withDescription("Test description")
            .ofType(TicketType.TASK)
            .createdBy(stubPmAgentId)

        val exception = assertFailsWith<IllegalStateException> {
            builder.build()
        }
        assertTrue(exception.message!!.contains("priority"))
    }

    @Test
    fun `build throws IllegalStateException when createdBy is missing`() {
        val builder = TicketBuilder()
            .withTitle("Test title")
            .withDescription("Test description")
            .ofType(TicketType.TASK)
            .withPriority(TicketPriority.HIGH)

        val exception = assertFailsWith<IllegalStateException> {
            builder.build()
        }
        assertTrue(exception.message!!.contains("createdBy"))
    }

    @Test
    fun `build throws IllegalStateException with all missing fields listed`() {
        val builder = TicketBuilder()

        val exception = assertFailsWith<IllegalStateException> {
            builder.build()
        }
        val message = exception.message!!
        assertTrue(message.contains("title"))
        assertTrue(message.contains("description"))
        assertTrue(message.contains("type"))
        assertTrue(message.contains("priority"))
        assertTrue(message.contains("createdBy"))
    }

    // ==================== DSL Function Tests ====================

    @Test
    fun `DSL function creates ticket with expected properties`() {
        val spec = ticket {
            withTitle("Add feature via DSL")
            withDescription("Testing DSL syntax")
            ofType(TicketType.FEATURE)
            withPriority(TicketPriority.HIGH)
            createdBy(stubPmAgentId)
        }

        assertEquals("Add feature via DSL", spec.title)
        assertEquals("Testing DSL syntax", spec.description)
        assertEquals(TicketType.FEATURE, spec.type)
        assertEquals(TicketPriority.HIGH, spec.priority)
        assertEquals(stubPmAgentId, spec.createdByAgentId)
    }

    @Test
    fun `DSL function throws IllegalStateException when required fields missing`() {
        assertFailsWith<IllegalStateException> {
            ticket {
                withTitle("Incomplete ticket")
                // Missing other required fields
            }
        }
    }

    @Test
    fun `DSL function supports all optional fields`() {
        val dueDate = Clock.System.now() + 7.days

        val spec = ticket {
            withTitle("Full ticket")
            withDescription("All fields populated")
            ofType(TicketType.TASK)
            withPriority(TicketPriority.MEDIUM)
            createdBy(stubPmAgentId)
            assignedTo(stubDevAgentId)
            dueBy(dueDate)
        }

        assertEquals(stubDevAgentId, spec.assignedToAgentId)
        assertEquals(dueDate, spec.dueDate)
    }

    // ==================== Integration Tests ====================

    @Test
    fun `create extension creates ticket and thread via orchestrator`() {
        runBlocking {
            val spec = ticket {
                withTitle("Integration Test Ticket")
                withDescription("Testing orchestrator integration")
                ofType(TicketType.FEATURE)
                withPriority(TicketPriority.HIGH)
                createdBy(stubPmAgentId)
            }

            val result = ticketOrchestrator.create(spec)

            assertTrue(result.isSuccess)
            val (ticket, thread) = result.getOrNull()!!

            // Verify ticket properties
            assertEquals("Integration Test Ticket", ticket.title)
            assertEquals("Testing orchestrator integration", ticket.description)
            assertEquals(TicketType.FEATURE, ticket.type)
            assertEquals(TicketPriority.HIGH, ticket.priority)
            assertEquals(stubPmAgentId, ticket.createdByAgentId)
            assertEquals(TicketStatus.BACKLOG, ticket.status)

            // Verify thread was created
            assertNotNull(thread)
            assertNotNull(thread.id)
        }
    }

    @Test
    fun `create extension handles assignment when specified`() {
        runBlocking {
            val spec = ticket {
                withTitle("Assigned Ticket")
                withDescription("Should be assigned to dev agent")
                ofType(TicketType.BUG)
                withPriority(TicketPriority.CRITICAL)
                createdBy(stubPmAgentId)
                assignedTo(stubDevAgentId)
            }

            val result = ticketOrchestrator.create(spec)

            assertTrue(result.isSuccess)
            val (ticket, _) = result.getOrNull()!!

            // The ticket from create returns the initial ticket, but assignment happens after
            // We need to fetch the ticket again to verify assignment
            val ticketResult = ticketRepository.getTicket(ticket.id)
            assertTrue(ticketResult.isSuccess)
            val updatedTicket = ticketResult.getOrNull()!!

            assertEquals(stubDevAgentId, updatedTicket.assignedAgentId)
        }
    }

    // ==================== Method Chaining Tests ====================

    @Test
    fun `method chaining works with all optional fields`() {
        val dueDate = Clock.System.now() + 14.days

        // Test that all methods can be chained in any order
        val spec = TicketBuilder()
            .createdBy(stubPmAgentId)
            .withPriority(TicketPriority.HIGH)
            .dueBy(dueDate)
            .ofType(TicketType.FEATURE)
            .assignedTo(stubDevAgentId)
            .withDescription("Full description")
            .withTitle("Full title")
            .build()

        assertEquals("Full title", spec.title)
        assertEquals("Full description", spec.description)
        assertEquals(TicketType.FEATURE, spec.type)
        assertEquals(TicketPriority.HIGH, spec.priority)
        assertEquals(stubPmAgentId, spec.createdByAgentId)
        assertEquals(stubDevAgentId, spec.assignedToAgentId)
        assertEquals(dueDate, spec.dueDate)
    }

    @Test
    fun `builder can be reused after build`() {
        val builder = TicketBuilder()
            .withTitle("First ticket")
            .withDescription("First description")
            .ofType(TicketType.TASK)
            .withPriority(TicketPriority.LOW)
            .createdBy(stubPmAgentId)

        val spec1 = builder.build()
        assertEquals("First ticket", spec1.title)

        // Modify builder for second ticket
        val spec2 = builder
            .withTitle("Second ticket")
            .withDescription("Second description")
            .build()

        assertEquals("Second ticket", spec2.title)
        assertEquals("Second description", spec2.description)
    }

    @Test
    fun `full integration with all fields and orchestrator`() {
        runBlocking {
            val dueDate = Clock.System.now() + 7.days

            val spec = ticket {
                withTitle("Complete Feature Implementation")
                withDescription("Implement the complete feature with all requirements")
                ofType(TicketType.FEATURE)
                withPriority(TicketPriority.HIGH)
                createdBy(stubPmAgentId)
                assignedTo(stubDevAgentId)
                dueBy(dueDate)
            }

            val result = ticketOrchestrator.create(spec)

            assertTrue(result.isSuccess)
            val (ticket, thread) = result.getOrNull()!!

            // Verify all ticket properties
            assertEquals("Complete Feature Implementation", ticket.title)
            assertEquals("Implement the complete feature with all requirements", ticket.description)
            assertEquals(TicketType.FEATURE, ticket.type)
            assertEquals(TicketPriority.HIGH, ticket.priority)
            assertEquals(stubPmAgentId, ticket.createdByAgentId)
            assertNotNull(ticket.createdAt)
            assertNotNull(ticket.updatedAt)

            // Verify thread was created
            assertNotNull(thread)
            assertTrue(thread.messages.isNotEmpty())

            // Verify assignment
            val ticketResult = ticketRepository.getTicket(ticket.id)
            val updatedTicket = ticketResult.getOrNull()!!
            assertEquals(stubDevAgentId, updatedTicket.assignedAgentId)
        }
    }
}
