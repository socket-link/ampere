package link.socket.ampere.api

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.agents.events.messages.DefaultThreadViewService
import link.socket.ampere.agents.events.tickets.DefaultTicketViewService
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.agents.service.MessageActionService
import link.socket.ampere.agents.service.TicketActionService
import link.socket.ampere.api.internal.DefaultAgentService
import link.socket.ampere.api.internal.DefaultEventService
import link.socket.ampere.api.internal.DefaultKnowledgeService
import link.socket.ampere.api.internal.DefaultOutcomeService
import link.socket.ampere.api.internal.DefaultStatusService
import link.socket.ampere.api.internal.DefaultThreadService
import link.socket.ampere.api.internal.DefaultTicketService
import link.socket.ampere.db.Database

/**
 * Tests for the SDK facade services.
 *
 * These tests verify that each Default*Service correctly delegates
 * to the underlying infrastructure.
 */
class AmpereInstanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var scope: CoroutineScope
    private lateinit var environmentService: EnvironmentService

    // Services under test
    private lateinit var ticketService: DefaultTicketService
    private lateinit var threadService: DefaultThreadService
    private lateinit var eventService: DefaultEventService
    private lateinit var outcomeService: DefaultOutcomeService
    private lateinit var knowledgeService: DefaultKnowledgeService
    private lateinit var statusService: DefaultStatusService
    private lateinit var agentService: DefaultAgentService

    private val now: Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        environmentService = EnvironmentService.create(database = database, scope = scope)
        environmentService.start()

        val sdkEventApi = environmentService.createEventApi("sdk-test")

        ticketService = DefaultTicketService(
            actionService = TicketActionService(
                ticketRepository = environmentService.ticketRepository,
                eventApi = sdkEventApi,
            ),
            viewService = DefaultTicketViewService(
                ticketRepository = environmentService.ticketRepository,
            ),
            ticketRepository = environmentService.ticketRepository,
        )

        threadService = DefaultThreadService(
            actionService = MessageActionService(
                messageRepository = environmentService.messageRepository,
                eventApi = sdkEventApi,
            ),
            viewService = DefaultThreadViewService(
                messageRepository = environmentService.messageRepository,
            ),
        )

        eventService = DefaultEventService(
            eventRelayService = environmentService.eventRelayService,
            eventRepository = environmentService.eventRepository,
        )

        outcomeService = DefaultOutcomeService(
            outcomeRepository = environmentService.outcomeMemoryRepository,
        )

        knowledgeService = DefaultKnowledgeService(
            knowledgeRepository = KnowledgeRepositoryImpl(database),
        )

        statusService = DefaultStatusService(
            threadViewService = DefaultThreadViewService(
                messageRepository = environmentService.messageRepository,
            ),
            ticketViewService = DefaultTicketViewService(
                ticketRepository = environmentService.ticketRepository,
            ),
            workspace = "/test/workspace",
        )

        agentService = DefaultAgentService(
            agentActionService = AgentActionService(eventApi = sdkEventApi),
            eventApi = sdkEventApi,
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        driver.close()
    }

    // ==================== TicketService Tests ====================

    @Test
    fun `TicketService create creates a ticket and returns it`() = runBlocking {
        val result = ticketService.create(
            title = "Test Ticket",
            description = "Test Description",
            priority = TicketPriority.HIGH,
            type = TicketType.BUG,
        )

        assertTrue(result.isSuccess)
        val ticket = result.getOrNull()!!
        assertEquals("Test Ticket", ticket.title)
        assertEquals("Test Description", ticket.description)
        assertEquals(TicketPriority.HIGH, ticket.priority)
        assertEquals(TicketType.BUG, ticket.type)
        assertEquals(TicketStatus.Backlog, ticket.status)
    }

    @Test
    fun `TicketService list returns active tickets after creation`() = runBlocking {
        ticketService.create(title = "Ticket A", description = "A")
        ticketService.create(title = "Ticket B", description = "B")

        val result = ticketService.list()
        assertTrue(result.isSuccess)
        val tickets = result.getOrNull()!!
        assertEquals(2, tickets.size)
    }

    @Test
    fun `TicketService transition updates ticket status`() = runBlocking {
        val ticket = ticketService.create(title = "Transition Test", description = "Test").getOrNull()!!

        // Backlog -> Ready
        val transitionResult = ticketService.transition(ticket.id, TicketStatus.Ready)
        assertTrue(transitionResult.isSuccess)

        val updated = ticketService.get(ticket.id).getOrNull()!!
        assertEquals(TicketStatus.Ready, updated.status)
    }

    @Test
    fun `TicketService assign assigns ticket to agent`() = runBlocking {
        val ticket = ticketService.create(title = "Assign Test", description = "Test").getOrNull()!!

        val assignResult = ticketService.assign(ticket.id, "engineer-1")
        assertTrue(assignResult.isSuccess)

        val updated = ticketService.get(ticket.id).getOrNull()!!
        assertEquals("engineer-1", updated.assignedAgentId)
    }

    // ==================== ThreadService Tests ====================

    @Test
    fun `ThreadService create creates a thread and returns it`() = runBlocking {
        val result = threadService.create(
            title = "Design Discussion",
            participantIds = listOf("pm-agent", "eng-agent"),
        )

        assertTrue(result.isSuccess)
        val thread = result.getOrNull()!!
        assertTrue(thread.id.isNotEmpty())
    }

    @Test
    fun `ThreadService post adds message to thread`() = runBlocking {
        val thread = threadService.create(title = "Test Thread").getOrNull()!!

        val msgResult = threadService.post(thread.id, "Hello from SDK test")

        assertTrue(msgResult.isSuccess)
        val message = msgResult.getOrNull()!!
        assertEquals("Hello from SDK test", message.content)
        assertEquals(thread.id, message.threadId)
    }

    @Test
    fun `ThreadService list returns active threads`() = runBlocking {
        threadService.create(title = "Thread 1")
        threadService.create(title = "Thread 2")

        val result = threadService.list()
        assertTrue(result.isSuccess)
        val threads = result.getOrNull()!!
        assertEquals(2, threads.size)
    }

    // ==================== AgentService Tests ====================

    @Test
    fun `AgentService pursue publishes goal and returns task ID`() = runBlocking {
        val result = agentService.pursue("Build authentication system")

        assertTrue(result.isSuccess)
        val taskId = result.getOrNull()!!
        assertTrue(taskId.startsWith("goal-"))
    }

    @Test
    fun `AgentService wake sends wake signal`() = runBlocking {
        val result = agentService.wake("test-agent")
        assertTrue(result.isSuccess)
    }

    // ==================== StatusService Tests ====================

    @Test
    fun `StatusService snapshot returns system state`() = runBlocking {
        // Create some data
        ticketService.create(title = "Active Ticket", description = "Test")
        threadService.create(title = "Active Thread")

        val result = statusService.snapshot()

        assertTrue(result.isSuccess)
        val snapshot = result.getOrNull()!!
        assertEquals(1, snapshot.activeTickets)
        assertEquals(1, snapshot.activeThreads)
        assertEquals("/test/workspace", snapshot.workspace)
    }

    @Test
    fun `StatusService snapshot returns empty state when no data exists`() = runBlocking {
        val result = statusService.snapshot()

        assertTrue(result.isSuccess)
        val snapshot = result.getOrNull()!!
        assertEquals(0, snapshot.activeTickets)
        assertEquals(0, snapshot.activeThreads)
    }

    // ==================== EventService Tests ====================

    @Test
    fun `EventService observe returns a flow`() {
        val flow = eventService.observe()
        assertNotNull(flow)
    }

    @Test
    fun `EventService query returns events in time range`() = runBlocking {
        // Create some activity to generate events
        ticketService.create(title = "Event Test", description = "Test")

        val from = now - kotlin.time.Duration.parse("1h")
        val to = Clock.System.now() + kotlin.time.Duration.parse("1h")

        val result = eventService.query(from, to)
        assertTrue(result.isSuccess)
    }
}
