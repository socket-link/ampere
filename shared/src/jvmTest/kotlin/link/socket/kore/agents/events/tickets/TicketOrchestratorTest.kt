package link.socket.kore.agents.events.tickets

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.kore.agents.core.AgentId
import link.socket.kore.agents.events.Database
import link.socket.kore.agents.events.Event
import link.socket.kore.agents.events.TicketEvent
import link.socket.kore.agents.events.api.EventHandler
import link.socket.kore.agents.events.bus.EventBus
import link.socket.kore.agents.events.messages.AgentMessageApi
import link.socket.kore.agents.events.messages.MessageRepository
import link.socket.kore.data.DEFAULT_JSON

class TicketOrchestratorTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var ticketRepository: TicketRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var eventBus: EventBus
    private lateinit var messageApi: AgentMessageApi
    private lateinit var orchestrator: TicketOrchestrator
    private val testScope = CoroutineScope(Dispatchers.Default)

    private val orchestratorAgentId: AgentId = "orchestrator-agent"
    private val creatorAgentId: AgentId = "creator-agent"
    private val assignedAgentId: AgentId = "assigned-agent"
    private val unauthorizedAgentId: AgentId = "unauthorized-agent"

    private val publishedEvents = mutableListOf<Event>()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database.Companion(driver)

        ticketRepository = TicketRepository(database)
        messageRepository = MessageRepository(DEFAULT_JSON, testScope, database)
        eventBus = EventBus(testScope)
        messageApi = AgentMessageApi(orchestratorAgentId, messageRepository, eventBus)

        // Subscribe to capture published events
        eventBus.subscribe(
            agentId = "test-subscriber",
            eventClassType = TicketEvent.TicketCreated.EVENT_CLASS_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            }
        )
        eventBus.subscribe(
            agentId = "test-subscriber",
            eventClassType = TicketEvent.TicketStatusChanged.EVENT_CLASS_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            }
        )
        eventBus.subscribe(
            agentId = "test-subscriber",
            eventClassType = TicketEvent.TicketAssigned.EVENT_CLASS_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            }
        )
        eventBus.subscribe(
            agentId = "test-subscriber",
            eventClassType = TicketEvent.TicketBlocked.EVENT_CLASS_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            }
        )

        orchestrator = TicketOrchestrator(
            ticketRepository = ticketRepository,
            eventBus = eventBus,
            messageApi = messageApi,
        )

        publishedEvents.clear()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== createTicket Tests ====================

    @Test
    fun `createTicket creates ticket and thread and publishes event`() {
        runBlocking {
            val result = orchestrator.createTicket(
                title = "Test Ticket",
                description = "Test description",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )

            assertTrue(result.isSuccess)
            val (ticket, thread) = result.getOrNull()!!

            // Verify ticket was created
            assertNotNull(ticket)
            assertEquals("Test Ticket", ticket.title)
            assertEquals("Test description", ticket.description)
            assertEquals(TicketType.TASK, ticket.type)
            assertEquals(TicketPriority.MEDIUM, ticket.priority)
            assertEquals(TicketStatus.BACKLOG, ticket.status)
            assertEquals(creatorAgentId, ticket.createdByAgentId)

            // Verify thread was created
            assertNotNull(thread)
            assertTrue(thread.messages.first().content.contains("Test Ticket"))

            // Verify ticket exists in repository
            val retrieved = ticketRepository.getTicket(ticket.id).getOrNull()
            assertNotNull(retrieved)
            assertEquals(ticket.id, retrieved.id)

            // Wait for async event publishing
            delay(100)

            // Verify TicketCreated event was published
            val createdEvents = publishedEvents.filterIsInstance<TicketEvent.TicketCreated>()
            assertTrue(createdEvents.isNotEmpty(), "TicketCreated event should be published")
            assertEquals(ticket.id, createdEvents.first().ticketId)
            assertEquals("Test Ticket", createdEvents.first().title)
        }
    }

    @Test
    fun `createTicket fails with blank title`() {
        runBlocking {
            val result = orchestrator.createTicket(
                title = "",
                description = "Test description",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.ValidationError>(error)
            assertTrue(error.message?.contains("blank") == true)
        }
    }

    @Test
    fun `createTicket sets correct urgency based on priority`() {
        runBlocking {
            // Create a CRITICAL priority ticket
            orchestrator.createTicket(
                title = "Critical Ticket",
                description = "Critical issue",
                type = TicketType.BUG,
                priority = TicketPriority.CRITICAL,
                createdByAgentId = creatorAgentId,
            )

            delay(100)

            val createdEvents = publishedEvents.filterIsInstance<TicketEvent.TicketCreated>()
            assertTrue(createdEvents.isNotEmpty())
            assertEquals(link.socket.kore.agents.events.Urgency.HIGH, createdEvents.first().urgency)
        }
    }

    // ==================== transitionTicketStatus Tests ====================

    @Test
    fun `transitionTicketStatus with valid transition publishes event and updates thread`() {
        runBlocking {
            // Create a ticket first
            val createResult = orchestrator.createTicket(
                title = "Transition Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!
            publishedEvents.clear()

            // Transition from BACKLOG to READY (valid transition)
            val result = orchestrator.transitionTicketStatus(
                ticketId = ticket.id,
                newStatus = TicketStatus.READY,
                actorAgentId = creatorAgentId,
            )

            assertTrue(result.isSuccess)
            val updatedTicket = result.getOrNull()!!
            assertEquals(TicketStatus.READY, updatedTicket.status)

            // Wait for async event publishing
            delay(100)

            // Verify TicketStatusChanged event was published
            val statusEvents = publishedEvents.filterIsInstance<TicketEvent.TicketStatusChanged>()
            assertTrue(statusEvents.isNotEmpty(), "TicketStatusChanged event should be published")
            assertEquals(ticket.id, statusEvents.first().ticketId)
            assertEquals(TicketStatus.BACKLOG, statusEvents.first().previousStatus)
            assertEquals(TicketStatus.READY, statusEvents.first().newStatus)
        }
    }

    @Test
    fun `transitionTicketStatus fails with invalid state transition`() {
        runBlocking {
            // Create a ticket
            val createResult = orchestrator.createTicket(
                title = "Invalid Transition",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Try invalid transition from BACKLOG to BLOCKED
            val result = orchestrator.transitionTicketStatus(
                ticketId = ticket.id,
                newStatus = TicketStatus.BLOCKED,
                actorAgentId = creatorAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.InvalidStateTransition>(error)
        }
    }

    @Test
    fun `transitionTicketStatus fails for non-existent ticket`() {
        runBlocking {
            val result = orchestrator.transitionTicketStatus(
                ticketId = "non-existent-id",
                newStatus = TicketStatus.READY,
                actorAgentId = creatorAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.TicketNotFound>(error)
        }
    }

    @Test
    fun `transitionTicketStatus rejects unauthorized agent`() {
        runBlocking {
            // Create a ticket
            val createResult = orchestrator.createTicket(
                title = "Unauthorized Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Try transition by unauthorized agent
            val result = orchestrator.transitionTicketStatus(
                ticketId = ticket.id,
                newStatus = TicketStatus.READY,
                actorAgentId = unauthorizedAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.ValidationError>(error)
            assertTrue(error.message?.contains("permission") == true)
        }
    }

    @Test
    fun `transitionTicketStatus allows assigned agent to modify`() {
        runBlocking {
            // Create and assign a ticket
            val createResult = orchestrator.createTicket(
                title = "Assigned Agent Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Assign to another agent
            orchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = assignedAgentId,
                assignerAgentId = creatorAgentId,
            )
            publishedEvents.clear()

            // Transition by assigned agent should succeed
            val result = orchestrator.transitionTicketStatus(
                ticketId = ticket.id,
                newStatus = TicketStatus.READY,
                actorAgentId = assignedAgentId,
            )

            assertTrue(result.isSuccess)
        }
    }

    // ==================== assignTicket Tests ====================

    @Test
    fun `assignTicket assigns ticket and publishes event`() {
        runBlocking {
            // Create a ticket
            val createResult = orchestrator.createTicket(
                title = "Assignment Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!
            publishedEvents.clear()

            // Assign to agent
            val result = orchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = assignedAgentId,
                assignerAgentId = creatorAgentId,
            )

            assertTrue(result.isSuccess)
            val updatedTicket = result.getOrNull()!!
            assertEquals(assignedAgentId, updatedTicket.assignedAgentId)

            // Wait for async event publishing
            delay(100)

            // Verify TicketAssigned event was published
            val assignEvents = publishedEvents.filterIsInstance<TicketEvent.TicketAssigned>()
            assertTrue(assignEvents.isNotEmpty(), "TicketAssigned event should be published")
            assertEquals(ticket.id, assignEvents.first().ticketId)
            assertEquals(assignedAgentId, assignEvents.first().assignedTo)
            assertEquals(creatorAgentId, assignEvents.first().assignedBy)
        }
    }

    @Test
    fun `assignTicket rejects assignment by unauthorized agent`() {
        runBlocking {
            // Create a ticket
            val createResult = orchestrator.createTicket(
                title = "Unauthorized Assignment",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Try assignment by unauthorized agent
            val result = orchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = assignedAgentId,
                assignerAgentId = unauthorizedAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.ValidationError>(error)
            assertTrue(error.message?.contains("permission") == true)
        }
    }

    @Test
    fun `assignTicket allows unassignment`() {
        runBlocking {
            // Create and assign a ticket
            val createResult = orchestrator.createTicket(
                title = "Unassignment Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            orchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = assignedAgentId,
                assignerAgentId = creatorAgentId,
            )
            publishedEvents.clear()

            // Unassign
            val result = orchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = null,
                assignerAgentId = creatorAgentId,
            )

            assertTrue(result.isSuccess)
            val updatedTicket = result.getOrNull()!!
            assertEquals(null, updatedTicket.assignedAgentId)

            // Verify event
            delay(100)
            val assignEvents = publishedEvents.filterIsInstance<TicketEvent.TicketAssigned>()
            assertTrue(assignEvents.isNotEmpty())
            assertEquals(null, assignEvents.first().assignedTo)
        }
    }

    // ==================== blockTicket Tests ====================

    @Test
    fun `blockTicket creates thread message requesting human intervention`() {
        runBlocking {
            // Create a ticket and transition to IN_PROGRESS
            val createResult = orchestrator.createTicket(
                title = "Block Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.HIGH,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Transition to READY -> IN_PROGRESS
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.READY, creatorAgentId)
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.IN_PROGRESS, creatorAgentId)
            publishedEvents.clear()

            // Block the ticket
            val blockReason = "Missing dependencies"
            val result = orchestrator.blockTicket(
                ticketId = ticket.id,
                blockingReason = blockReason,
                reportedByAgentId = creatorAgentId,
            )

            assertTrue(result.isSuccess)
            val updatedTicket = result.getOrNull()!!
            assertEquals(TicketStatus.BLOCKED, updatedTicket.status)

            // Wait for async event publishing
            delay(100)

            // Verify TicketBlocked event was published
            val blockedEvents = publishedEvents.filterIsInstance<TicketEvent.TicketBlocked>()
            assertTrue(blockedEvents.isNotEmpty(), "TicketBlocked event should be published")
            assertEquals(ticket.id, blockedEvents.first().ticketId)
            assertEquals(blockReason, blockedEvents.first().blockingReason)
            assertEquals(link.socket.kore.agents.events.Urgency.HIGH, blockedEvents.first().urgency)
        }
    }

    @Test
    fun `blockTicket fails for invalid state transition`() {
        runBlocking {
            // Create a ticket in BACKLOG status
            val createResult = orchestrator.createTicket(
                title = "Invalid Block",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Try to block from BACKLOG (invalid - can only block from IN_PROGRESS)
            val result = orchestrator.blockTicket(
                ticketId = ticket.id,
                blockingReason = "Test reason",
                reportedByAgentId = creatorAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.InvalidStateTransition>(error)
        }
    }

    // ==================== Full Lifecycle Tests ====================

    @Test
    fun `full ticket lifecycle from creation to completion`() {
        runBlocking {
            // Create ticket
            val createResult = orchestrator.createTicket(
                title = "Full Lifecycle Test",
                description = "Test the complete ticket workflow",
                type = TicketType.FEATURE,
                priority = TicketPriority.HIGH,
                createdByAgentId = creatorAgentId,
            )
            assertTrue(createResult.isSuccess)
            val (ticket, _) = createResult.getOrNull()!!

            // Verify initial status
            var current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.BACKLOG, current.status)

            // Assign to agent
            orchestrator.assignTicket(ticket.id, assignedAgentId, creatorAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(assignedAgentId, current.assignedAgentId)

            // Transition: BACKLOG -> READY
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.READY, creatorAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.READY, current.status)

            // Transition: READY -> IN_PROGRESS
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.IN_PROGRESS, assignedAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.IN_PROGRESS, current.status)

            // Transition: IN_PROGRESS -> IN_REVIEW
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.IN_REVIEW, assignedAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.IN_REVIEW, current.status)

            // Transition: IN_REVIEW -> DONE
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.DONE, assignedAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.DONE, current.status)
            assertTrue(current.isComplete)
        }
    }

    @Test
    fun `ticket lifecycle with blocking and unblocking`() {
        runBlocking {
            // Create and progress ticket to IN_PROGRESS
            val createResult = orchestrator.createTicket(
                title = "Block Lifecycle",
                description = "Test blocking workflow",
                type = TicketType.BUG,
                priority = TicketPriority.CRITICAL,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.READY, creatorAgentId)
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.IN_PROGRESS, creatorAgentId)

            // Block the ticket
            orchestrator.blockTicket(ticket.id, "Waiting for external API", creatorAgentId)
            var current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.BLOCKED, current.status)
            assertTrue(current.isBlocked)

            // Unblock: BLOCKED -> IN_PROGRESS
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.IN_PROGRESS, creatorAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.IN_PROGRESS, current.status)

            // Complete: IN_PROGRESS -> DONE
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.DONE, creatorAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertTrue(current.isComplete)
        }
    }

    @Test
    fun `multiple events are published for complex workflows`() {
        runBlocking {
            publishedEvents.clear()

            // Create ticket
            val createResult = orchestrator.createTicket(
                title = "Multiple Events",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Assign
            orchestrator.assignTicket(ticket.id, assignedAgentId, creatorAgentId)

            // Transition through states
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.READY, creatorAgentId)
            orchestrator.transitionTicketStatus(ticket.id, TicketStatus.IN_PROGRESS, assignedAgentId)

            // Wait for all events
            delay(100)

            // Verify all events were published
            val createdEvents = publishedEvents.filterIsInstance<TicketEvent.TicketCreated>()
            val assignedEvents = publishedEvents.filterIsInstance<TicketEvent.TicketAssigned>()
            val statusEvents = publishedEvents.filterIsInstance<TicketEvent.TicketStatusChanged>()

            assertEquals(1, createdEvents.size)
            assertEquals(1, assignedEvents.size)
            assertEquals(2, statusEvents.size)
        }
    }

    @Test
    fun `permission validation works correctly across different agents`() {
        runBlocking {
            // Create ticket as creator
            val createResult = orchestrator.createTicket(
                title = "Permission Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = creatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Creator can modify
            val creatorResult = orchestrator.transitionTicketStatus(
                ticket.id, TicketStatus.READY, creatorAgentId
            )
            assertTrue(creatorResult.isSuccess)

            // Unauthorized agent cannot modify
            val unauthorizedResult = orchestrator.transitionTicketStatus(
                ticket.id, TicketStatus.IN_PROGRESS, unauthorizedAgentId
            )
            assertTrue(unauthorizedResult.isFailure)

            // Assign to a new agent
            orchestrator.assignTicket(ticket.id, assignedAgentId, creatorAgentId)

            // Assigned agent can now modify
            val assignedResult = orchestrator.transitionTicketStatus(
                ticket.id, TicketStatus.IN_PROGRESS, assignedAgentId
            )
            assertTrue(assignedResult.isSuccess)

            // Unauthorized agent still cannot modify
            val stillUnauthorizedResult = orchestrator.transitionTicketStatus(
                ticket.id, TicketStatus.DONE, unauthorizedAgentId
            )
            assertTrue(stillUnauthorizedResult.isFailure)
        }
    }
}
