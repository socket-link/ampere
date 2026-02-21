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

        agentService = DefaultAgentService(
            agentActionService = AgentActionService(eventApi = sdkEventApi),
            eventApi = sdkEventApi,
        )

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
            agentService = agentService,
            workspace = "/test/workspace",
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        driver.close()
    }

    // ==================== TicketService Tests ====================

    @Test
    fun `TicketService create with builder DSL creates a ticket`() = runBlocking {
        val result = ticketService.create("Test Ticket") {
            description("Test Description")
            priority(TicketPriority.HIGH)
            type(TicketType.BUG)
        }

        assertTrue(result.isSuccess)
        val ticket = result.getOrNull()!!
        assertEquals("Test Ticket", ticket.title)
        assertEquals("Test Description", ticket.description)
        assertEquals(TicketPriority.HIGH, ticket.priority)
        assertEquals(TicketType.BUG, ticket.type)
        assertEquals(TicketStatus.Backlog, ticket.status)
    }

    @Test
    fun `TicketService create without builder uses defaults`() = runBlocking {
        val result = ticketService.create("Simple Ticket")

        assertTrue(result.isSuccess)
        val ticket = result.getOrNull()!!
        assertEquals("Simple Ticket", ticket.title)
        assertEquals(TicketPriority.MEDIUM, ticket.priority)
        assertEquals(TicketType.TASK, ticket.type)
    }

    @Test
    fun `TicketService list returns active tickets after creation`() = runBlocking {
        ticketService.create("Ticket A")
        ticketService.create("Ticket B")

        val result = ticketService.list()
        assertTrue(result.isSuccess)
        val tickets = result.getOrNull()!!
        assertEquals(2, tickets.size)
    }

    @Test
    fun `TicketService transition updates ticket status`() = runBlocking {
        val ticket = ticketService.create("Transition Test").getOrNull()!!

        val transitionResult = ticketService.transition(ticket.id, TicketStatus.Ready)
        assertTrue(transitionResult.isSuccess)

        val updated = ticketService.get(ticket.id).getOrNull()!!
        assertEquals(TicketStatus.Ready, updated.status)
    }

    @Test
    fun `TicketService assign assigns ticket to agent`() = runBlocking {
        val ticket = ticketService.create("Assign Test").getOrNull()!!

        val assignResult = ticketService.assign(ticket.id, "engineer-1")
        assertTrue(assignResult.isSuccess)

        val updated = ticketService.get(ticket.id).getOrNull()!!
        assertEquals("engineer-1", updated.assignedAgentId)
    }

    // ==================== ThreadService Tests ====================

    @Test
    fun `ThreadService create with builder DSL creates a thread`() = runBlocking {
        val result = threadService.create("Design Discussion") {
            participants("pm-agent", "eng-agent")
        }

        assertTrue(result.isSuccess)
        val thread = result.getOrNull()!!
        assertTrue(thread.id.isNotEmpty())
    }

    @Test
    fun `ThreadService create without builder uses defaults`() = runBlocking {
        val result = threadService.create("Simple Thread")

        assertTrue(result.isSuccess)
        val thread = result.getOrNull()!!
        assertTrue(thread.id.isNotEmpty())
    }

    @Test
    fun `ThreadService post adds message to thread`() = runBlocking {
        val thread = threadService.create("Test Thread").getOrNull()!!

        val msgResult = threadService.post(thread.id, "Hello from SDK test")

        assertTrue(msgResult.isSuccess)
        val message = msgResult.getOrNull()!!
        assertEquals("Hello from SDK test", message.content)
        assertEquals(thread.id, message.threadId)
    }

    @Test
    fun `ThreadService list returns active threads`() = runBlocking {
        threadService.create("Thread 1")
        threadService.create("Thread 2")

        val result = threadService.list()
        assertTrue(result.isSuccess)
        val threads = result.getOrNull()!!
        assertEquals(2, threads.size)
    }

    @Test
    fun `ThreadService observe returns a flow`() {
        val thread = runBlocking { threadService.create("Observable Thread").getOrNull()!! }
        val flow = threadService.observe(thread.id)
        assertNotNull(flow)
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

    @Test
    fun `AgentService listAll returns empty when no team configured`() = runBlocking {
        val agents = agentService.listAll()
        assertTrue(agents.isEmpty())
    }

    @Test
    fun `AgentService inspect fails when no team configured`() = runBlocking {
        val result = agentService.inspect("nonexistent")
        assertTrue(result.isFailure)
    }

    // ==================== StatusService Tests ====================

    @Test
    fun `StatusService snapshot returns system state`() = runBlocking {
        ticketService.create("Active Ticket")
        threadService.create("Active Thread")

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

    @Test
    fun `StatusService health returns a flow`() {
        val flow = statusService.health()
        assertNotNull(flow)
    }

    // ==================== EventService Tests ====================

    @Test
    fun `EventService observe returns a flow`() {
        val flow = eventService.observe()
        assertNotNull(flow)
    }

    @Test
    fun `EventService query returns events in time range`() = runBlocking {
        ticketService.create("Event Test")

        val from = now - kotlin.time.Duration.parse("1h")
        val to = Clock.System.now() + kotlin.time.Duration.parse("1h")

        val result = eventService.query(from, to)
        assertTrue(result.isSuccess)
    }

    // ==================== OutcomeService Tests ====================

    @Test
    fun `OutcomeService stats returns aggregated statistics`() = runBlocking {
        val result = outcomeService.stats()

        assertTrue(result.isSuccess)
        val stats = result.getOrNull()!!
        assertEquals(0, stats.totalOutcomes)
        assertEquals(0.0, stats.successRate)
    }

    // ==================== KnowledgeService Tests ====================

    @Test
    fun `KnowledgeService provenance fails for nonexistent knowledge`() = runBlocking {
        val result = knowledgeService.provenance("nonexistent-id")
        assertTrue(result.isFailure)
    }
}
