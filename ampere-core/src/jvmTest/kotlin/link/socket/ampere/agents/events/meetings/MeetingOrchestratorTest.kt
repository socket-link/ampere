package link.socket.ampere.agents.events.meetings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.concept.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.concept.status.MeetingStatus
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.task.AssignedTo
import link.socket.ampere.agents.domain.concept.task.MeetingTask.AgendaItem
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.db.Database
import link.socket.ampere.util.randomUUID

class MeetingOrchestratorTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var messageApi: AgentMessageApi
    private lateinit var orchestrator: MeetingOrchestrator
    private val testScope = CoroutineScope(Dispatchers.Default)

    private val stubJson = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val stubScheduledBy = EventSource.Agent("scheduler-agent")

    private val orchestratorAgentId: AgentId = "orchestrator-agent"
    private val publishedEvents = mutableListOf<Event>()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database.Companion(driver)

        meetingRepository = MeetingRepository(stubJson, testScope, database)
        messageRepository = MessageRepository(stubJson, testScope, database)
        eventSerialBus = EventSerialBus(testScope)
        messageApi = AgentMessageApi(orchestratorAgentId, messageRepository, eventSerialBus)

        // Subscribe to capture published events
        eventSerialBus.subscribe(
            agentId = "test-subscriber",
            eventType = MeetingEvent.MeetingScheduled.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            },
        )
        eventSerialBus.subscribe(
            agentId = "test-subscriber",
            eventType = MeetingEvent.MeetingStarted.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            },
        )
        eventSerialBus.subscribe(
            agentId = "test-subscriber",
            eventType = MeetingEvent.AgendaItemStarted.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            },
        )
        eventSerialBus.subscribe(
            agentId = "test-subscriber",
            eventType = MeetingEvent.MeetingCompleted.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                publishedEvents.add(event)
            },
        )

        orchestrator = MeetingOrchestrator(
            repository = meetingRepository,
            eventSerialBus = eventSerialBus,
            messageApi = messageApi,
        )

        publishedEvents.clear()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Helper Methods ====================

    private fun createTestMeeting(
        id: String = randomUUID(),
        title: String = "Test Meeting",
        scheduledFor: Instant = Clock.System.now() + 1.hours,
        agendaItems: List<AgendaItem> = listOf(
            AgendaItem(
                id = randomUUID(),
                title = "Topic 1",
                status = TaskStatus.Pending,
                assignedTo = AssignedTo.Agent("agent-alpha"),
            ),
            AgendaItem(
                id = randomUUID(),
                title = "Topic 2",
                status = TaskStatus.Pending,
                assignedTo = AssignedTo.Agent("agent-beta"),
            ),
        ),
        requiredParticipants: List<AssignedTo> = listOf(
            AssignedTo.Agent("agent-alpha"),
            AssignedTo.Agent("agent-beta"),
        ),
    ): Meeting = Meeting(
        id = id,
        type = MeetingType.AdHoc("Test reason"),
        status = MeetingStatus.Scheduled(scheduledFor = scheduledFor),
        invitation = MeetingInvitation(
            title = title,
            agenda = agendaItems,
            requiredParticipants = requiredParticipants,
        ),
    )

    // ==================== scheduleMeeting Tests ====================

    @Test
    fun `scheduleMeeting creates meeting and publishes event`() {
        runBlocking {
            val meeting = createTestMeeting()

            val result = orchestrator.scheduleMeeting(meeting, stubScheduledBy)

            assertTrue(result.isSuccess)
            val scheduledMeeting = result.getOrNull()
            assertNotNull(scheduledMeeting)
            assertEquals(meeting.id, scheduledMeeting.id)

            // Verify meeting exists in repository
            val retrieved = meetingRepository.getMeeting(meeting.id).getOrNull()
            assertNotNull(retrieved)
            assertEquals(meeting.invitation.title, retrieved.invitation.title)

            // Wait a bit for async event publishing
            delay(100)

            // Verify MeetingScheduled event was published
            val scheduledEvent = publishedEvents.filterIsInstance<MeetingEvent.MeetingScheduled>()
            assertTrue(scheduledEvent.isNotEmpty(), "MeetingScheduled event should be published")
            assertEquals(meeting.id, scheduledEvent.first().meeting.id)
        }
    }

    @Test
    fun `scheduleMeeting fails for past scheduled time`() {
        runBlocking {
            val pastTime = Clock.System.now() - 1.hours
            val meeting = createTestMeeting(scheduledFor = pastTime)

            val result = orchestrator.scheduleMeeting(meeting, stubScheduledBy)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertEquals(true, error.message?.contains("future time"))
        }
    }

    @Test
    fun `scheduleMeeting fails for meeting with no participants`() {
        runBlocking {
            val meeting = createTestMeeting(requiredParticipants = emptyList())

            val result = orchestrator.scheduleMeeting(meeting, stubScheduledBy)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertEquals(true, error.message?.contains("participant"))
        }
    }

    @Test
    fun `scheduleMeeting fails for non-scheduled status`() {
        runBlocking {
            val meeting = Meeting(
                id = randomUUID(),
                type = MeetingType.AdHoc("Test"),
                status = MeetingStatus.Delayed("Some reason"),
                invitation = MeetingInvitation(
                    title = "Test",
                    agenda = emptyList(),
                    requiredParticipants = listOf(AssignedTo.Agent("agent-1")),
                ),
            )

            val result = orchestrator.scheduleMeeting(meeting, stubScheduledBy)

            assertTrue(result.isFailure)
        }
    }

    // ==================== startMeeting Tests ====================

    @Test
    fun `startMeeting transitions meeting to in progress`() {
        runBlocking {
            // First schedule a meeting
            val meeting = createTestMeeting()
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            publishedEvents.clear()

            // Now start it
            val result = orchestrator.startMeeting(meeting.id)

            assertTrue(result.isSuccess)

            // Verify meeting status changed
            val retrieved = meetingRepository.getMeeting(meeting.id).getOrNull()
            assertNotNull(retrieved)
            assertTrue(retrieved.status is MeetingStatus.InProgress)

            // Wait for async event publishing
            delay(100)

            // Verify MeetingStarted event was published
            val startedEvents = publishedEvents.filterIsInstance<MeetingEvent.MeetingStarted>()
            assertTrue(startedEvents.isNotEmpty(), "MeetingStarted event should be published")
            assertEquals(meeting.id, startedEvents.first().meetingId)
        }
    }

    @Test
    fun `startMeeting fails for non-existent meeting`() {
        runBlocking {
            val result = orchestrator.startMeeting("non-existent-id")

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertEquals(true, error.message?.contains("not found"))
        }
    }

    @Test
    fun `startMeeting fails for meeting not in scheduled status`() {
        runBlocking {
            // Create and start a meeting
            val meeting = createTestMeeting()
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            // Try to start again
            val result = orchestrator.startMeeting(meeting.id)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertEquals(true, error.message?.contains("SCHEDULED"))
        }
    }

    // ==================== advanceAgenda Tests ====================

    @Test
    fun `advanceAgenda returns next pending agenda item`() {
        runBlocking {
            // Schedule and start meeting
            val agendaItems = listOf(
                AgendaItem(
                    id = "ai-1",
                    title = "First Topic",
                    status = TaskStatus.Pending,
                    assignedTo = AssignedTo.Agent("agent-alpha"),
                ),
                AgendaItem(
                    id = "ai-2",
                    title = "Second Topic",
                    status = TaskStatus.Pending,
                    assignedTo = AssignedTo.Agent("agent-beta"),
                ),
            )
            val meeting = createTestMeeting(agendaItems = agendaItems)
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)
            publishedEvents.clear()

            // Advance to first item
            val result = orchestrator.advanceAgenda(meeting.id)

            assertTrue(result.isSuccess)
            val item = result.getOrNull()
            assertNotNull(item)
            assertEquals("First Topic", item.title)
            assertTrue(item.status is TaskStatus.InProgress)

            // Wait for async event publishing
            delay(100)

            // Verify AgendaItemStarted event was published
            val agendaEvents = publishedEvents.filterIsInstance<MeetingEvent.AgendaItemStarted>()
            assertTrue(agendaEvents.isNotEmpty(), "AgendaItemStarted event should be published")
        }
    }

    @Test
    fun `advanceAgenda returns null when all items complete`() {
        runBlocking {
            // Create meeting with no agenda items
            val meeting = createTestMeeting(agendaItems = emptyList())
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            val result = orchestrator.advanceAgenda(meeting.id)

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull())
        }
    }

    @Test
    fun `advanceAgenda fails for meeting not in progress`() {
        runBlocking {
            val meeting = createTestMeeting()
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            // Don't start the meeting

            val result = orchestrator.advanceAgenda(meeting.id)

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertEquals(true, error.message?.contains("IN_PROGRESS"))
        }
    }

    // ==================== completeMeeting Tests ====================

    @Test
    fun `completeMeeting transitions meeting to completed with outcomes`() {
        runBlocking {
            // Schedule and start meeting
            val meeting = createTestMeeting()
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)
            publishedEvents.clear()

            // Complete with outcomes
            val outcomes = listOf(
                MeetingOutcome.DecisionMade(
                    id = randomUUID(),
                    description = "We decided to proceed",
                    decidedBy = EventSource.Agent("agent-alpha"),
                ),
                MeetingOutcome.ActionItem(
                    id = randomUUID(),
                    assignedTo = AssignedTo.Agent("agent-beta"),
                    description = "Implement the feature",
                ),
            )

            val result = orchestrator.completeMeeting(meeting.id, outcomes)

            assertTrue(result.isSuccess)

            // Verify meeting status changed
            val retrieved = meetingRepository.getMeeting(meeting.id).getOrNull()
            assertNotNull(retrieved)
            assertTrue(retrieved.status is MeetingStatus.Completed)

            assertEquals(2, retrieved.status.outcomes?.size ?: 0)

            // Wait for async event publishing
            delay(100)

            // Verify MeetingCompleted event was published
            val completedEvents = publishedEvents.filterIsInstance<MeetingEvent.MeetingCompleted>()
            assertTrue(completedEvents.isNotEmpty(), "MeetingCompleted event should be published")
            assertEquals(meeting.id, completedEvents.first().meetingId)
            assertEquals(2, completedEvents.first().outcomes.size)
        }
    }

    @Test
    fun `completeMeeting works with empty outcomes`() {
        runBlocking {
            val meeting = createTestMeeting()
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            val result = orchestrator.completeMeeting(meeting.id, emptyList())

            assertTrue(result.isSuccess)

            val retrieved = meetingRepository.getMeeting(meeting.id).getOrNull()
            assertNotNull(retrieved)
            assertTrue(retrieved.status is MeetingStatus.Completed)
        }
    }

    @Test
    fun `completeMeeting fails for meeting not in progress`() {
        runBlocking {
            val meeting = createTestMeeting()
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            // Don't start the meeting

            val result = orchestrator.completeMeeting(meeting.id, emptyList())

            assertTrue(result.isFailure)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error.message?.contains("IN_PROGRESS") == true)
        }
    }

    @Test
    fun `completeMeeting fails for non-existent meeting`() {
        runBlocking {
            val result = orchestrator.completeMeeting("non-existent-id", emptyList())

            assertTrue(result.isFailure)
        }
    }

    // ==================== Full Lifecycle Tests ====================

    @Test
    fun `full meeting lifecycle from schedule to complete`() {
        runBlocking {
            // Create meeting
            val agendaItems = listOf(
                AgendaItem(
                    id = "lifecycle-ai-1",
                    title = "Topic 1",
                    status = TaskStatus.Pending,
                    assignedTo = AssignedTo.Agent("agent-alpha"),
                ),
                AgendaItem(
                    id = "lifecycle-ai-2",
                    title = "Topic 2",
                    status = TaskStatus.Pending,
                    assignedTo = AssignedTo.Agent("agent-beta"),
                ),
            )
            val meeting = createTestMeeting(
                id = "lifecycle-meeting",
                title = "Full Lifecycle Test",
                agendaItems = agendaItems,
            )

            // Schedule
            val scheduleResult = orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            assertTrue(scheduleResult.isSuccess)

            // Verify scheduled status
            var retrieved = meetingRepository.getMeeting(meeting.id).getOrNull()
            assertNotNull(retrieved)
            assertTrue(retrieved.status is MeetingStatus.Scheduled)

            // Start
            val startResult = orchestrator.startMeeting(meeting.id)
            assertTrue(startResult.isSuccess)

            retrieved = meetingRepository.getMeeting(meeting.id).getOrNull()
            assertNotNull(retrieved)
            assertTrue(retrieved.status is MeetingStatus.InProgress)

            // Advance through agenda items
            val firstItem = orchestrator.advanceAgenda(meeting.id).getOrNull()
            assertNotNull(firstItem)
            assertEquals("Topic 1", firstItem.title)

            // In a real scenario, we'd mark the first item as completed before advancing
            // For this test, we'll just complete the meeting

            // Complete
            val outcomes = listOf(
                MeetingOutcome.DecisionMade(
                    id = randomUUID(),
                    description = "Test decision",
                    decidedBy = EventSource.Agent("agent-alpha"),
                ),
            )
            val completeResult = orchestrator.completeMeeting(meeting.id, outcomes)
            assertTrue(completeResult.isSuccess)

            // Verify final state
            retrieved = meetingRepository.getMeeting(meeting.id).getOrNull()
            assertNotNull(retrieved)
            assertTrue(retrieved.status is MeetingStatus.Completed)

            assertNotNull(retrieved.status.outcomes)
            assertEquals(1, retrieved.status.outcomes.size)
        }
    }

    @Test
    fun `state transitions are validated correctly`() {
        runBlocking {
            val meeting = createTestMeeting()
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)

            // Can't complete from scheduled
            val completeFromScheduled = orchestrator.completeMeeting(meeting.id, emptyList())
            assertTrue(completeFromScheduled.isFailure)

            // Can't advance agenda from scheduled
            val advanceFromScheduled = orchestrator.advanceAgenda(meeting.id)
            assertTrue(advanceFromScheduled.isFailure)

            // Start the meeting
            orchestrator.startMeeting(meeting.id)

            // Can't start again
            val startAgain = orchestrator.startMeeting(meeting.id)
            assertTrue(startAgain.isFailure)

            // Can advance and complete now
            val advance = orchestrator.advanceAgenda(meeting.id)
            assertTrue(advance.isSuccess)

            val complete = orchestrator.completeMeeting(meeting.id, emptyList())
            assertTrue(complete.isSuccess)

            // Can't complete again
            val completeAgain = orchestrator.completeMeeting(meeting.id, emptyList())
            assertTrue(completeAgain.isFailure)
        }
    }
}
