package link.socket.ampere.agents.events.tickets

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
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.escalation.Escalation
import link.socket.ampere.agents.events.meetings.MeetingOrchestrator
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.meetings.MeetingSchedulingService
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

class TicketOrchestratorTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    private lateinit var messageRepository: MessageRepository
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var ticketRepository: TicketRepository

    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var messageApi: AgentMessageApi
    private lateinit var meetingOrchestrator: MeetingOrchestrator
    private lateinit var ticketOrchestrator: TicketOrchestrator

    private val testScope = CoroutineScope(Dispatchers.Default)
    private val stubOrchestratorAgentId: AgentId = "orchestrator-agent"
    private val stubCreatorAgentId: AgentId = "creator-agent"
    private val stubAssignedAgentId: AgentId = "assigned-agent"
    private val stubUnauthorizedAgentId: AgentId = "unauthorized-agent"

    private val publishedEvents = mutableListOf<Event>()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database.Companion(driver)

        messageRepository = MessageRepository(DEFAULT_JSON, testScope, database)
        meetingRepository = MeetingRepository(DEFAULT_JSON, testScope, database)
        ticketRepository = TicketRepository(database)

        eventSerialBus = EventSerialBus(testScope)
        messageApi = AgentMessageApi(stubOrchestratorAgentId, messageRepository, eventSerialBus)

        meetingOrchestrator = MeetingOrchestrator(
            repository = meetingRepository,
            eventSerialBus = eventSerialBus,
            messageApi = messageApi,
        )

        // Create a MeetingSchedulingService that delegates to the meetingOrchestrator
        val meetingSchedulingService = MeetingSchedulingService { meeting, scheduledBy ->
            meetingOrchestrator.scheduleMeeting(meeting, scheduledBy)
        }

        ticketOrchestrator = TicketOrchestrator(
            ticketRepository = ticketRepository,
            eventSerialBus = eventSerialBus,
            messageApi = messageApi,
            meetingSchedulingService = meetingSchedulingService,
        )

        // Subscribe to capture published events
        eventSerialBus.subscribe(
            agentId = "test-subscriber",
            eventType = TicketEvent.TicketCreated.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            },
        )
        eventSerialBus.subscribe(
            agentId = "test-subscriber",
            eventType = TicketEvent.TicketStatusChanged.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            },
        )
        eventSerialBus.subscribe(
            agentId = "test-subscriber",
            eventType = TicketEvent.TicketAssigned.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            },
        )
        eventSerialBus.subscribe(
            agentId = "test-subscriber",
            eventType = TicketEvent.TicketBlocked.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            },
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
            val result = ticketOrchestrator.createTicket(
                title = "Test Ticket",
                description = "Test description",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )

            assertTrue(result.isSuccess)
            val (ticket, thread) = result.getOrNull()!!

            // Verify ticket was created
            assertNotNull(ticket)
            assertEquals("Test Ticket", ticket.title)
            assertEquals("Test description", ticket.description)
            assertEquals(TicketType.TASK, ticket.type)
            assertEquals(TicketPriority.MEDIUM, ticket.priority)
            assertEquals(TicketStatus.Backlog, ticket.status)
            assertEquals(stubCreatorAgentId, ticket.createdByAgentId)

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
            val result = ticketOrchestrator.createTicket(
                title = "",
                description = "Test description",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.ValidationError>(error)
            assertEquals(error.message.contains("blank"), true)
        }
    }

    @Test
    fun `createTicket sets correct urgency based on priority`() {
        runBlocking {
            // Create a CRITICAL priority ticket
            ticketOrchestrator.createTicket(
                title = "Critical Ticket",
                description = "Critical issue",
                type = TicketType.BUG,
                priority = TicketPriority.CRITICAL,
                createdByAgentId = stubCreatorAgentId,
            )

            delay(100)

            val createdEvents = publishedEvents.filterIsInstance<TicketEvent.TicketCreated>()
            assertTrue(createdEvents.isNotEmpty())
            assertEquals(Urgency.HIGH, createdEvents.first().urgency)
        }
    }

    // ==================== transitionTicketStatus Tests ====================

    @Test
    fun `transitionTicketStatus with valid transition publishes event and updates thread`() {
        runBlocking {
            // Create a ticket first
            val createResult = ticketOrchestrator.createTicket(
                title = "Transition Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!
            publishedEvents.clear()

            // Transition from BACKLOG to READY (valid transition)
            val result = ticketOrchestrator.transitionTicketStatus(
                ticketId = ticket.id,
                newStatus = TicketStatus.Ready,
                actorAgentId = stubCreatorAgentId,
            )

            assertTrue(result.isSuccess)
            val updatedTicket = result.getOrNull()!!
            assertEquals(TicketStatus.Ready, updatedTicket.status)

            // Wait for async event publishing
            delay(100)

            // Verify TicketStatusChanged event was published
            val statusEvents = publishedEvents.filterIsInstance<TicketEvent.TicketStatusChanged>()
            assertTrue(statusEvents.isNotEmpty(), "TicketStatusChanged event should be published")
            assertEquals(ticket.id, statusEvents.first().ticketId)
            assertEquals(TicketStatus.Backlog, statusEvents.first().previousStatus)
            assertEquals(TicketStatus.Ready, statusEvents.first().newStatus)
        }
    }

    @Test
    fun `transitionTicketStatus fails with invalid state transition`() {
        runBlocking {
            // Create a ticket
            val createResult = ticketOrchestrator.createTicket(
                title = "Invalid Transition",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Try invalid transition from BACKLOG to BLOCKED
            val result = ticketOrchestrator.transitionTicketStatus(
                ticketId = ticket.id,
                newStatus = TicketStatus.Blocked,
                actorAgentId = stubCreatorAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.InvalidStateTransition>(error)
        }
    }

    @Test
    fun `transitionTicketStatus fails for non-existent ticket`() {
        runBlocking {
            val result = ticketOrchestrator.transitionTicketStatus(
                ticketId = "non-existent-id",
                newStatus = TicketStatus.Ready,
                actorAgentId = stubCreatorAgentId,
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
            val createResult = ticketOrchestrator.createTicket(
                title = "Unauthorized Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Try transition by unauthorized agent
            val result = ticketOrchestrator.transitionTicketStatus(
                ticketId = ticket.id,
                newStatus = TicketStatus.Ready,
                actorAgentId = stubUnauthorizedAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.ValidationError>(error)
            assertEquals(error.message.contains("permission"), true)
        }
    }

    @Test
    fun `transitionTicketStatus allows assigned agent to modify`() {
        runBlocking {
            // Create and assign a ticket
            val createResult = ticketOrchestrator.createTicket(
                title = "Assigned Agent Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Assign to another agent
            ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = stubAssignedAgentId,
                assignerAgentId = stubCreatorAgentId,
            )
            publishedEvents.clear()

            // Transition by assigned agent should succeed
            val result = ticketOrchestrator.transitionTicketStatus(
                ticketId = ticket.id,
                newStatus = TicketStatus.Ready,
                actorAgentId = stubAssignedAgentId,
            )

            assertTrue(result.isSuccess)
        }
    }

    // ==================== assignTicket Tests ====================

    @Test
    fun `assignTicket assigns ticket and publishes event`() {
        runBlocking {
            // Create a ticket
            val createResult = ticketOrchestrator.createTicket(
                title = "Assignment Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!
            publishedEvents.clear()

            // Assign to agent
            val result = ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = stubAssignedAgentId,
                assignerAgentId = stubCreatorAgentId,
            )

            assertTrue(result.isSuccess)
            val updatedTicket = result.getOrNull()!!
            assertEquals(stubAssignedAgentId, updatedTicket.assignedAgentId)

            // Wait for async event publishing
            delay(100)

            // Verify TicketAssigned event was published
            val assignEvents = publishedEvents.filterIsInstance<TicketEvent.TicketAssigned>()
            assertTrue(assignEvents.isNotEmpty(), "TicketAssigned event should be published")
            assertEquals(ticket.id, assignEvents.first().ticketId)
            assertEquals(stubAssignedAgentId, assignEvents.first().assignedTo)
            assertEquals(stubCreatorAgentId, assignEvents.first().eventSource.getIdentifier())
        }
    }

    @Test
    fun `assignTicket rejects assignment by unauthorized agent`() {
        runBlocking {
            // Create a ticket
            val createResult = ticketOrchestrator.createTicket(
                title = "Unauthorized Assignment",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Try assignment by unauthorized agent
            val result = ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = stubAssignedAgentId,
                assignerAgentId = stubUnauthorizedAgentId,
            )

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertIs<TicketError.ValidationError>(error)
            assertEquals(error.message.contains("permission"), true)
        }
    }

    @Test
    fun `assignTicket allows unassignment`() {
        runBlocking {
            // Create and assign a ticket
            val createResult = ticketOrchestrator.createTicket(
                title = "Unassignment Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = stubAssignedAgentId,
                assignerAgentId = stubCreatorAgentId,
            )
            publishedEvents.clear()

            // Unassign
            val result = ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = null,
                assignerAgentId = stubCreatorAgentId,
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
            val createResult = ticketOrchestrator.createTicket(
                title = "Block Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.HIGH,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Transition to READY -> IN_PROGRESS
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.Ready, stubCreatorAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.InProgress, stubCreatorAgentId)
            publishedEvents.clear()

            // Block the ticket
            val blockReason = "Missing dependencies"
            val result = ticketOrchestrator.blockTicket(
                ticketId = ticket.id,
                blockingReason = blockReason,
                escalationType = Escalation.Budget.ResourceAllocation,
                reportedByAgentId = stubCreatorAgentId,
            )

            assertTrue(result.isSuccess)
            val updatedTicket = result.getOrNull()!!
            assertEquals(TicketStatus.Blocked, updatedTicket.status)

            // Wait for async event publishing
            delay(100)

            // Verify TicketBlocked event was published
            val blockedEvents = publishedEvents.filterIsInstance<TicketEvent.TicketBlocked>()
            assertTrue(blockedEvents.isNotEmpty(), "TicketBlocked event should be published")
            assertEquals(ticket.id, blockedEvents.first().ticketId)
            assertEquals(blockReason, blockedEvents.first().blockingReason)
            assertEquals(Urgency.HIGH, blockedEvents.first().urgency)
        }
    }

    @Test
    fun `blockTicket fails for invalid state transition`() {
        runBlocking {
            // Create a ticket in BACKLOG status
            val createResult = ticketOrchestrator.createTicket(
                title = "Invalid Block",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Try to block from BACKLOG (invalid - can only block from IN_PROGRESS)
            val result = ticketOrchestrator.blockTicket(
                ticketId = ticket.id,
                blockingReason = "Test reason",
                escalationType = Escalation.Budget.ResourceAllocation,
                reportedByAgentId = stubCreatorAgentId,
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
            val createResult = ticketOrchestrator.createTicket(
                title = "Full Lifecycle Test",
                description = "Test the complete ticket workflow",
                type = TicketType.FEATURE,
                priority = TicketPriority.HIGH,
                createdByAgentId = stubCreatorAgentId,
            )
            assertTrue(createResult.isSuccess)
            val (ticket, _) = createResult.getOrNull()!!

            // Verify initial status
            var current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.Backlog, current.status)

            // Assign to agent
            ticketOrchestrator.assignTicket(ticket.id, stubAssignedAgentId, stubCreatorAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(stubAssignedAgentId, current.assignedAgentId)

            // Transition: BACKLOG -> READY
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.Ready, stubCreatorAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.Ready, current.status)

            // Transition: READY -> IN_PROGRESS
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.InProgress, stubAssignedAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.InProgress, current.status)

            // Transition: IN_PROGRESS -> IN_REVIEW
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.InReview, stubAssignedAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.InReview, current.status)

            // Transition: IN_REVIEW -> DONE
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.Done, stubAssignedAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.Done, current.status)
            assertTrue(current.isComplete)
        }
    }

    @Test
    fun `ticket lifecycle with blocking and unblocking`() {
        runBlocking {
            // Create and progress ticket to IN_PROGRESS
            val createResult = ticketOrchestrator.createTicket(
                title = "Block Lifecycle",
                description = "Test blocking workflow",
                type = TicketType.BUG,
                priority = TicketPriority.CRITICAL,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.Ready, stubCreatorAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.InProgress, stubCreatorAgentId)

            // Block the ticket
            ticketOrchestrator.blockTicket(
                ticketId = ticket.id,
                blockingReason = "Waiting for external API",
                escalationType = Escalation.Budget.ResourceAllocation,
                reportedByAgentId = stubCreatorAgentId,
            )
            var current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.Blocked, current.status)
            assertTrue(current.isBlocked)

            // Unblock: BLOCKED -> IN_PROGRESS
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.InProgress, stubCreatorAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertEquals(TicketStatus.InProgress, current.status)

            // Complete: IN_PROGRESS -> DONE
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.Done, stubCreatorAgentId)
            current = ticketRepository.getTicket(ticket.id).getOrNull()!!
            assertTrue(current.isComplete)
        }
    }

    @Test
    fun `multiple events are published for complex workflows`() {
        runBlocking {
            publishedEvents.clear()

            // Create ticket
            val createResult = ticketOrchestrator.createTicket(
                title = "Multiple Events",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Assign
            ticketOrchestrator.assignTicket(ticket.id, stubAssignedAgentId, stubCreatorAgentId)

            // Transition through states
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.Ready, stubCreatorAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.InProgress, stubAssignedAgentId)

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
            val createResult = ticketOrchestrator.createTicket(
                title = "Permission Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!

            // Creator can modify
            val creatorResult = ticketOrchestrator.transitionTicketStatus(
                ticket.id,
                TicketStatus.Ready,
                stubCreatorAgentId,
            )
            assertTrue(creatorResult.isSuccess)

            // Unauthorized agent cannot modify
            val unauthorizedResult = ticketOrchestrator.transitionTicketStatus(
                ticket.id,
                TicketStatus.InProgress,
                stubUnauthorizedAgentId,
            )
            assertTrue(unauthorizedResult.isFailure)

            // Assign to a new agent
            ticketOrchestrator.assignTicket(ticket.id, stubAssignedAgentId, stubCreatorAgentId)

            // Assigned agent can now modify
            val assignedResult = ticketOrchestrator.transitionTicketStatus(
                ticket.id,
                TicketStatus.InProgress,
                stubAssignedAgentId,
            )
            assertTrue(assignedResult.isSuccess)

            // Unauthorized agent still cannot modify
            val stillUnauthorizedResult = ticketOrchestrator.transitionTicketStatus(
                ticket.id,
                TicketStatus.Done,
                stubUnauthorizedAgentId,
            )
            assertTrue(stillUnauthorizedResult.isFailure)
        }
    }
}
