package link.socket.kore.agents.events.tickets

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.kore.agents.core.AgentId
import link.socket.kore.agents.core.AssignedTo
import link.socket.kore.agents.events.Database
import link.socket.kore.agents.events.Event
import link.socket.kore.agents.events.TicketEvent
import link.socket.kore.agents.events.api.EventHandler
import link.socket.kore.agents.events.bus.EventBus
import link.socket.kore.agents.events.meetings.MeetingOrchestrator
import link.socket.kore.agents.events.meetings.MeetingRepository
import link.socket.kore.agents.events.messages.AgentMessageApi
import link.socket.kore.agents.events.messages.MessageRepository
import link.socket.kore.agents.events.tasks.AgendaItem
import link.socket.kore.agents.events.tasks.Task
import link.socket.kore.data.DEFAULT_JSON
import link.socket.kore.util.randomUUID

class TicketMeetingIntegrationTest {

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
    private val stubCreatorAgentId: AgentId = "creator-agent"
    private val stubAssignedAgentId: AgentId = "assigned-agent"
    private val stubPmAgentId: AgentId = "pm-agent"

    private val publishedEvents = mutableListOf<Event>()

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

        ticketOrchestrator = TicketOrchestrator(
            ticketRepository = ticketRepository,
            eventBus = eventBus,
            messageApi = messageApi,
            meetingOrchestrator = meetingOrchestrator,
        )

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
            eventClassType = TicketEvent.TicketBlocked.EVENT_CLASS_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            }
        )
        eventBus.subscribe(
            agentId = "test-subscriber",
            eventClassType = TicketEvent.TicketMeetingScheduled.EVENT_CLASS_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            }
        )

        publishedEvents.clear()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== scheduleTicketMeeting Tests ====================

    @Test
    fun `scheduleTicketMeeting creates meeting and links to ticket`() {
        runBlocking {
            // Create a ticket first
            val createResult = ticketOrchestrator.createTicket(
                title = "Meeting Test Ticket",
                description = "Test description",
                type = TicketType.TASK,
                priority = TicketPriority.HIGH,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createResult.getOrNull()!!
            publishedEvents.clear()

            val scheduledTime = Clock.System.now() + 2.hours
            val participants = listOf(
                AssignedTo.Agent(stubAssignedAgentId),
                AssignedTo.Agent(stubPmAgentId),
            )
            val agendaItems = listOf(
                AgendaItem(
                    id = randomUUID(),
                    topic = "Discuss implementation approach",
                    assignedTo = AssignedTo.Agent(stubAssignedAgentId),
                    status = Task.Status.Pending(),
                )
            )

            // Schedule a meeting for the ticket
            val result = ticketOrchestrator.scheduleTicketMeeting(
                ticketId = ticket.id,
                meetingTitle = "Implementation Discussion",
                scheduledTime = scheduledTime,
                agendaItems = agendaItems,
                requiredParticipants = participants,
                optionalParticipants = null,
            )

            assertTrue(result.isSuccess)
            val meetingId = result.getOrNull()!!
            assertNotNull(meetingId)

            // Verify ticket-meeting association exists
            val meetingsResult = ticketRepository.getMeetingsForTicket(ticket.id)
            assertTrue(meetingsResult.isSuccess)
            val ticketMeetings = meetingsResult.getOrNull()!!
            assertEquals(1, ticketMeetings.size)
            assertEquals(meetingId, ticketMeetings.first().meetingId)

            // Wait for async event publishing
            delay(100)

            // Verify TicketMeetingScheduled event was published
            val meetingEvents = publishedEvents.filterIsInstance<TicketEvent.TicketMeetingScheduled>()
            assertTrue(meetingEvents.isNotEmpty(), "TicketMeetingScheduled event should be published")

            // Verify the latest event
            val event = meetingEvents.first()
            assertEquals(ticket.id, event.ticketId)
            assertEquals(meetingId, event.meetingId)
            assertEquals(scheduledTime, event.scheduledTime)
            assertEquals(2, event.requiredParticipants.size)
        }
    }

    // ==================== Automatic Meeting Scheduling Tests ====================

    @Test
    fun `blockTicket with decision keyword automatically schedules meeting`() {
        runBlocking {
            // Create a ticket and assign it
            val createTicketResult = ticketOrchestrator.createTicket(
                title = "Auto Meeting Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.HIGH,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createTicketResult.getOrNull()!!

            // Assign the ticket
            ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = stubAssignedAgentId,
                assignerAgentId = stubCreatorAgentId,
            )

            // Transition to IN_PROGRESS (BACKLOG -> READY -> IN_PROGRESS)
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.READY, stubCreatorAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.IN_PROGRESS, stubCreatorAgentId)

            publishedEvents.clear()

            // Block with a reason that indicates need for decision meeting
            val blockTicketResult = ticketOrchestrator.blockTicket(
                ticketId = ticket.id,
                blockingReason = "Need architecture decision on database schema",
                reportedByAgentId = stubAssignedAgentId,
                assignedToAgentId = stubAssignedAgentId,
            )

            assertTrue(blockTicketResult.isSuccess)

            // Wait for async event publishing
            delay(150)

            // Verify TicketBlocked event was published
            val blockedEvents = publishedEvents.filterIsInstance<TicketEvent.TicketBlocked>()
            assertTrue(blockedEvents.isNotEmpty(), "TicketBlocked event should be published")

            // Verify TicketMeetingScheduled event was published
            val meetingEvents = publishedEvents.filterIsInstance<TicketEvent.TicketMeetingScheduled>()
            assertTrue(meetingEvents.isNotEmpty(), "TicketMeetingScheduled event should be published")

            // Verify meeting was linked to ticket
            val meetingsResult = ticketRepository.getMeetingsForTicket(ticket.id)
            assertTrue(meetingsResult.isSuccess)
            val ticketMeetings = meetingsResult.getOrNull()!!
            assertEquals(1, ticketMeetings.size)

            // Verify meeting participants include assigned agent
            val meetingEvent = meetingEvents.first()
            val agentParticipants = meetingEvent.requiredParticipants.filterIsInstance<AssignedTo.Agent>()
            assertTrue(agentParticipants.any { it.agentId == stubAssignedAgentId }, "Should include assigned agent")
        }
    }

    @Test
    fun `blockTicket with human keyword includes human in meeting participants`() {
        runBlocking {
            // Create a ticket and assign it
            val createTicketResult = ticketOrchestrator.createTicket(
                title = "Human Approval Test",
                description = "Test",
                type = TicketType.FEATURE,
                priority = TicketPriority.CRITICAL,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createTicketResult.getOrNull()!!

            // Assign the ticket
            ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = stubAssignedAgentId,
                assignerAgentId = stubCreatorAgentId,
            )

            // Transition to IN_PROGRESS
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.READY, stubCreatorAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.IN_PROGRESS, stubCreatorAgentId)

            publishedEvents.clear()

            // Block with a reason that indicates need for human approval
            val result = ticketOrchestrator.blockTicket(
                ticketId = ticket.id,
                blockingReason = "Requires human approval for security permission changes",
                reportedByAgentId = stubAssignedAgentId,
                assignedToAgentId = stubAssignedAgentId,
            )

            assertTrue(result.isSuccess)

            // Wait for async event publishing
            delay(150)

            // Verify TicketMeetingScheduled event was published
            val meetingEvents = publishedEvents.filterIsInstance<TicketEvent.TicketMeetingScheduled>()
            assertTrue(meetingEvents.isNotEmpty(), "TicketMeetingScheduled event should be published")

            // Verify meeting participants include human
            val meetingEvent = meetingEvents.first()
            val hasHuman = meetingEvent.requiredParticipants.any { it is AssignedTo.Human }
            assertTrue(hasHuman, "Should include human participant for approval requests")
        }
    }

    @Test
    fun `blockTicket without decision keywords does not schedule meeting`() {
        runBlocking {
            // Create a ticket and assign it
            val createTicketResult = ticketOrchestrator.createTicket(
                title = "No Meeting Test",
                description = "Test",
                type = TicketType.BUG,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createTicketResult.getOrNull()!!

            // Assign the ticket
            ticketOrchestrator.assignTicket(
                ticketId = ticket.id,
                targetAgentId = stubAssignedAgentId,
                assignerAgentId = stubCreatorAgentId,
            )

            // Transition ticket to IN_PROGRESS
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.READY, stubCreatorAgentId)
            ticketOrchestrator.transitionTicketStatus(ticket.id, TicketStatus.IN_PROGRESS, stubCreatorAgentId)

            publishedEvents.clear()

            // Block with a generic reason that won't trigger a meeting
            val blockTicketResult = ticketOrchestrator.blockTicket(
                ticketId = ticket.id,
                blockingReason = "Waiting for external API to be available",
                reportedByAgentId = stubAssignedAgentId,
            )

            assertTrue(blockTicketResult.isSuccess)

            // Wait for async event publishing
            delay(150)

            // Verify TicketBlocked event was published
            val blockedEvents = publishedEvents.filterIsInstance<TicketEvent.TicketBlocked>()
            assertTrue(blockedEvents.isNotEmpty(), "TicketBlocked event should be published")

            // Verify no meeting was scheduled
            val meetingEvents = publishedEvents.filterIsInstance<TicketEvent.TicketMeetingScheduled>()
            assertTrue(meetingEvents.isEmpty(), "TicketMeetingScheduled event should NOT be published")

            // Verify no ticket-meeting association exists
            val meetingsResult = ticketRepository.getMeetingsForTicket(ticket.id)
            assertTrue(meetingsResult.isSuccess)
            val ticketMeetings = meetingsResult.getOrNull()!!
            assertTrue(ticketMeetings.isEmpty(), "No meeting should be linked to ticket")
        }
    }

    // ==================== Ticket-Meeting Association Tests ====================

    @Test
    fun `getMeetingsForTicket returns all associated meetings`() {
        runBlocking {
            // Create a ticket
            val createTicketResult = ticketOrchestrator.createTicket(
                title = "Multiple Meetings Test",
                description = "Test",
                type = TicketType.SPIKE,
                priority = TicketPriority.HIGH,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createTicketResult.getOrNull()!!

            val scheduledTime = Clock.System.now() + 2.hours
            val participants = listOf(AssignedTo.Agent(stubAssignedAgentId))

            // Schedule multiple meetings for the ticket
            val scheduleMeetingResult1 = ticketOrchestrator.scheduleTicketMeeting(
                ticketId = ticket.id,
                meetingTitle = "First Meeting",
                scheduledTime = scheduledTime,
                agendaItems = emptyList(),
                requiredParticipants = participants,
                optionalParticipants = null,
            )
            val scheduleMeetingResult2 = ticketOrchestrator.scheduleTicketMeeting(
                ticketId = ticket.id,
                meetingTitle = "Second Meeting",
                scheduledTime = scheduledTime + 1.hours,
                agendaItems = emptyList(),
                requiredParticipants = participants,
                optionalParticipants = null,
            )

            assertTrue(scheduleMeetingResult1.isSuccess)
            assertTrue(scheduleMeetingResult2.isSuccess)

            // Verify both meetings are associated with the ticket
            val getTicketMeetingsResult = ticketRepository.getMeetingsForTicket(ticket.id)
            assertTrue(getTicketMeetingsResult.isSuccess)
            val ticketMeetings = getTicketMeetingsResult.getOrNull()!!
            assertEquals(2, ticketMeetings.size)

            // Verify both meeting IDs are present
            val meetingIds = ticketMeetings.map { it.meetingId }.toSet()
            assertTrue(meetingIds.contains(scheduleMeetingResult1.getOrNull()))
            assertTrue(meetingIds.contains(scheduleMeetingResult2.getOrNull()))
        }
    }

    @Test
    fun `getTicketsForMeeting returns associated ticket`() {
        runBlocking {
            // Create a ticket
            val createTicketResult = ticketOrchestrator.createTicket(
                title = "Reverse Lookup Test",
                description = "Test",
                type = TicketType.TASK,
                priority = TicketPriority.MEDIUM,
                createdByAgentId = stubCreatorAgentId,
            )
            val (ticket, _) = createTicketResult.getOrNull()!!

            val scheduledTime = Clock.System.now() + 2.hours
            val participants = listOf(AssignedTo.Agent(stubAssignedAgentId))

            // Schedule a meeting
            val meetingScheduledResult = ticketOrchestrator.scheduleTicketMeeting(
                ticketId = ticket.id,
                meetingTitle = "Test Meeting",
                scheduledTime = scheduledTime,
                agendaItems = emptyList(),
                requiredParticipants = participants,
                optionalParticipants = null,
            )

            // Query tickets for the meeting
            val meetingId = meetingScheduledResult.getOrNull()!!
            val getTicketMeetingsResult = ticketRepository.getTicketsForMeeting(meetingId)
            assertTrue(getTicketMeetingsResult.isSuccess)

            // Verify latest event
            val ticketMeetings = getTicketMeetingsResult.getOrNull()!!
            assertEquals(1, ticketMeetings.size)
            assertEquals(ticket.id, ticketMeetings.first().ticketId)
        }
    }
}
