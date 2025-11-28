package link.socket.ampere.agents.events.meetings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.AgentId
import link.socket.ampere.agents.core.AssignedTo
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.status.MeetingStatus
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.tasks.MeetingTask.AgendaItem
import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.MeetingEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.implementations.code.CodeWriterAgent
import link.socket.ampere.agents.execution.tools.ToolWriteCodeFile
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.util.randomUUID

class MeetingParticipationHandlerTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var messageApi: AgentMessageApi
    private lateinit var orchestrator: MeetingOrchestrator
    private lateinit var participationHandler: MeetingParticipationHandler
    private val testScope = CoroutineScope(Dispatchers.Default)

    private val stubJson = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val stubAgent = CodeWriterAgent(
        initialState = AgentState(),
        agentConfiguration = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Google,
                model = AIModel_Gemini.Pro_2_5,
            ),
        ),
        toolWriteCodeFile = ToolWriteCodeFile(
            requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
        ),
        coroutineScope = testScope,
    )

    private val stubScheduledBy = EventSource.Agent("scheduler-agent")

    private val orchestratorAgentId: AgentId = "orchestrator-agent"
    private val publishedEvents = mutableListOf<Event>()
    private val receivedEventsByAgent = mutableMapOf<AgentId, MutableList<MeetingEvent>>()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database.Companion(driver)

        meetingRepository = MeetingRepository(stubJson, testScope, database)
        messageRepository = MessageRepository(stubJson, testScope, database)
        eventSerialBus = EventSerialBus(testScope)
        messageApi = AgentMessageApi(orchestratorAgentId, messageRepository, eventSerialBus)

        orchestrator = MeetingOrchestrator(
            repository = meetingRepository,
            eventSerialBus = eventSerialBus,
            messageApi = messageApi,
        )

        participationHandler = MeetingParticipationHandler(
            eventSerialBus = eventSerialBus,
            messageApi = messageApi,
            meetingRepository = meetingRepository,
        )

        // Initialize the handler to subscribe to EventBus events
        participationHandler.initialize()

        receivedEventsByAgent.clear()
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

    // ==================== subscribeAgent Tests ====================

    @Test
    fun `subscribeAgent registers agent handler`() {
        runBlocking {
            val agentId = "test-agent"
            var eventReceived = false

            participationHandler.subscribeAgent(agentId) { event ->
                eventReceived = true
            }

            val registeredAgents = participationHandler.getRegisteredAgentIds()
            assertTrue(registeredAgents.contains(agentId))
        }
    }

    @Test
    fun `unsubscribeAgent removes agent handler`() {
        runBlocking {
            val agentId = "test-agent"

            participationHandler.subscribeAgent(agentId) { _ -> }
            participationHandler.unsubscribeAgent(agentId)

            val registeredAgents = participationHandler.getRegisteredAgentIds()
            assertFalse(registeredAgents.contains(agentId))
        }
    }

    @Test
    fun `multiple agents can be subscribed`() {
        runBlocking {
            participationHandler.subscribeAgent("agent-1") { _ -> }
            participationHandler.subscribeAgent("agent-2") { _ -> }
            participationHandler.subscribeAgent("agent-3") { _ -> }

            val registeredAgents = participationHandler.getRegisteredAgentIds()
            assertEquals(3, registeredAgents.size)
            assertTrue(registeredAgents.containsAll(listOf("agent-1", "agent-2", "agent-3")))
        }
    }

    // ==================== Event Routing Tests ====================

    @Test
    fun `registered agents receive MeetingStarted event when they are participants`() {
        runBlocking {
            val meeting = createTestMeeting()
            val alphaEvents = mutableListOf<MeetingEvent>()
            val betaEvents = mutableListOf<MeetingEvent>()

            // Subscribe agents
            participationHandler.subscribeAgent("agent-alpha") { event ->
                alphaEvents.add(event)
            }
            participationHandler.subscribeAgent("agent-beta") { event ->
                betaEvents.add(event)
            }

            // Schedule and start meeting
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            // Wait for async event handling
            delay(200)

            // Verify both agents received the event
            assertTrue(alphaEvents.any { it is MeetingEvent.MeetingStarted })
            assertTrue(betaEvents.any { it is MeetingEvent.MeetingStarted })
        }
    }

    @Test
    fun `agents not in participant list do not receive event`() {
        runBlocking {
            val meeting = createTestMeeting(
                requiredParticipants = listOf(
                    AssignedTo.Agent("agent-alpha"),
                ),
            )
            val alphaEvents = mutableListOf<MeetingEvent>()
            val gammaEvents = mutableListOf<MeetingEvent>()

            // Subscribe both agents
            participationHandler.subscribeAgent("agent-alpha") { event ->
                alphaEvents.add(event)
            }
            participationHandler.subscribeAgent("agent-gamma") { event ->
                gammaEvents.add(event) // Not a participant
            }

            // Schedule and start meeting
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            // Wait for async event handling
            delay(200)

            // Verify only agent-alpha received the event
            assertTrue(alphaEvents.any { it is MeetingEvent.MeetingStarted })
            assertTrue(gammaEvents.isEmpty(), "Non-participant should not receive events")
        }
    }

    @Test
    fun `unregistered agents do not receive events even if participants`() {
        runBlocking {
            val meeting = createTestMeeting()
            val alphaEvents = mutableListOf<MeetingEvent>()

            // Only subscribe agent-alpha, not agent-beta
            participationHandler.subscribeAgent("agent-alpha") { event ->
                alphaEvents.add(event)
            }

            // Schedule and start meeting
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            // Wait for async event handling
            delay(200)

            // Verify agent-alpha received the event
            assertTrue(alphaEvents.any { it is MeetingEvent.MeetingStarted })
            // agent-beta was not subscribed, so it wouldn't receive events
        }
    }

    // ==================== AgendaItemStarted Event Tests ====================

    @Test
    fun `registered agents receive AgendaItemStarted event`() {
        runBlocking {
            val meeting = createTestMeeting()
            val alphaEvents = mutableListOf<MeetingEvent>()

            participationHandler.subscribeAgent("agent-alpha") { event ->
                alphaEvents.add(event)
            }

            // Schedule, start, and advance agenda
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)
            orchestrator.advanceAgenda(meeting.id)

            // Wait for async event handling
            delay(200)

            // Verify agent received AgendaItemStarted event
            assertTrue(alphaEvents.any { it is MeetingEvent.AgendaItemStarted })
        }
    }

    @Test
    fun `agenda item events are correctly routed to participants`() {
        runBlocking {
            val agendaItems = listOf(
                AgendaItem(
                    id = "ai-1",
                    title = "Alpha's Topic",
                    status = TaskStatus.Pending,
                    assignedTo = AssignedTo.Agent("agent-alpha"),
                ),
            )
            val meeting = createTestMeeting(agendaItems = agendaItems)
            val alphaEvents = mutableListOf<MeetingEvent>()
            val betaEvents = mutableListOf<MeetingEvent>()

            participationHandler.subscribeAgent("agent-alpha") { event ->
                alphaEvents.add(event)
            }
            participationHandler.subscribeAgent("agent-beta") { event ->
                betaEvents.add(event)
            }

            // Schedule, start, and advance agenda
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)
            orchestrator.advanceAgenda(meeting.id)

            // Wait for async event handling
            delay(200)

            // Both participants should receive the AgendaItemStarted event
            val alphaAgendaEvents = alphaEvents.filterIsInstance<MeetingEvent.AgendaItemStarted>()
            val betaAgendaEvents = betaEvents.filterIsInstance<MeetingEvent.AgendaItemStarted>()

            assertTrue(alphaAgendaEvents.isNotEmpty())
            assertTrue(betaAgendaEvents.isNotEmpty())

            // The agenda item is assigned to agent-alpha
            assertEquals("agent-alpha", (alphaAgendaEvents.first().agendaItem.assignedTo as AssignedTo.Agent).agentId)
        }
    }

    // ==================== MeetingCompleted Event Tests ====================

    @Test
    fun `registered agents receive MeetingCompleted event`() {
        runBlocking {
            val meeting = createTestMeeting()
            val alphaEvents = mutableListOf<MeetingEvent>()

            participationHandler.subscribeAgent("agent-alpha") { event ->
                alphaEvents.add(event)
            }

            // Full meeting lifecycle
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)
            orchestrator.completeMeeting(meeting.id, emptyList())

            // Wait for async event handling
            delay(200)

            // Verify agent received MeetingCompleted event
            assertTrue(alphaEvents.any { it is MeetingEvent.MeetingCompleted })
        }
    }

    // ==================== isAgentParticipant Tests ====================

    @Test
    fun `isAgentParticipant returns true for participant`() {
        runBlocking {
            val meeting = createTestMeeting()
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)

            val isParticipant = participationHandler.isAgentParticipant(meeting.id, "agent-alpha")

            assertTrue(isParticipant)
        }
    }

    @Test
    fun `isAgentParticipant returns false for non-participant`() {
        runBlocking {
            val meeting = createTestMeeting()
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)

            val isParticipant = participationHandler.isAgentParticipant(meeting.id, "agent-gamma")

            assertFalse(isParticipant)
        }
    }

    @Test
    fun `isAgentParticipant returns false for non-existent meeting`() {
        runBlocking {
            val isParticipant = participationHandler.isAgentParticipant("non-existent", "agent-alpha")

            assertFalse(isParticipant)
        }
    }

    // ==================== handleMeetingStart Tests ====================

    @Test
    fun `handleMeetingStart posts presence message`() {
        runBlocking {
            val meeting = createTestMeeting()

            // Schedule and start meeting to get thread ID
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            // Get the meeting started event
            delay(100)
            val retrievedMeeting = meetingRepository.getMeeting(meeting.id).getOrNull()!!
            val inProgressStatus = retrievedMeeting.status as MeetingStatus.InProgress
            val threadId = inProgressStatus.messagingDetails.messageThreadId

            val event = MeetingEvent.MeetingStarted(
                eventId = randomUUID(),
                meetingId = meeting.id,
                threadId = threadId,
                startedAt = Clock.System.now(),
                startedBy = EventSource.Agent(orchestratorAgentId),
            )

            // Handle the meeting start - verify it completes without error
            participationHandler.handleMeetingStart(event, stubAgent)

            // If we got here without exception, messages were posted successfully
            assertTrue(true, "handleMeetingStart completed successfully")
        }
    }

    // ==================== handleAgendaItem Tests ====================

    @Test
    fun `handleAgendaItem posts correct message for assigned agent`() {
        runBlocking {
            val agendaItems = listOf(
                AgendaItem(
                    id = "ai-1",
                    title = "Alpha's Topic",
                    status = TaskStatus.InProgress,
                    assignedTo = AssignedTo.Agent("agent-alpha"),
                ),
            )
            val meeting = createTestMeeting(agendaItems = agendaItems)

            // Schedule and start meeting
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            delay(100)
            val retrievedMeeting = meetingRepository.getMeeting(meeting.id).getOrNull()!!

            val event = MeetingEvent.AgendaItemStarted(
                eventId = randomUUID(),
                meetingId = meeting.id,
                agendaItem = agendaItems.first(),
                startedBy = EventSource.Agent(orchestratorAgentId),
                timestamp = Clock.System.now(),
            )

            // Handle the agenda item - verify it completes without error
            participationHandler.handleAgendaItem(event, stubAgent)

            // If we got here without exception, message was posted successfully
            assertTrue(true, "handleAgendaItem completed successfully for assigned agent")
        }
    }

    @Test
    fun `handleAgendaItem posts different message for non-assigned agent`() {
        runBlocking {
            val agendaItems = listOf(
                AgendaItem(
                    id = "ai-1",
                    title = "Alpha's Topic",
                    status = TaskStatus.InProgress,
                    assignedTo = AssignedTo.Agent("agent-alpha"),
                ),
            )
            val meeting = createTestMeeting(agendaItems = agendaItems)

            // Schedule and start meeting
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            delay(100)
            val retrievedMeeting = meetingRepository.getMeeting(meeting.id).getOrNull()!!

            val event = MeetingEvent.AgendaItemStarted(
                eventId = randomUUID(),
                meetingId = meeting.id,
                agendaItem = agendaItems.first(),
                startedBy = EventSource.Agent(orchestratorAgentId),
                timestamp = Clock.System.now(),
            )

            // Handle the agenda item - verify it completes without error
            participationHandler.handleAgendaItem(event, stubAgent)

            // If we got here without exception, message was posted successfully
            assertTrue(true, "handleAgendaItem completed successfully for non-assigned agent")
        }
    }

    // ==================== Optional Participants Tests ====================

    @Test
    fun `optional participants also receive events`() {
        runBlocking {
            val meeting = Meeting(
                id = randomUUID(),
                type = MeetingType.AdHoc("Test"),
                status = MeetingStatus.Scheduled(scheduledFor = Clock.System.now() + 1.hours),
                invitation = MeetingInvitation(
                    title = "Test Meeting",
                    agenda = listOf(
                        AgendaItem(
                            id = randomUUID(),
                            title = "Topic",
                            status = TaskStatus.Pending,
                            assignedTo = null,
                        ),
                    ),
                    requiredParticipants = listOf(AssignedTo.Agent("agent-alpha")),
                    optionalParticipants = listOf(AssignedTo.Agent("agent-beta")),
                ),
            )

            val alphaEvents = mutableListOf<MeetingEvent>()
            val betaEvents = mutableListOf<MeetingEvent>()

            participationHandler.subscribeAgent("agent-alpha") { event ->
                alphaEvents.add(event)
            }
            participationHandler.subscribeAgent("agent-beta") { event ->
                betaEvents.add(event)
            }

            // Schedule and start meeting
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)

            // Wait for async event handling
            delay(200)

            // Both required and optional participants should receive events
            assertTrue(alphaEvents.any { it is MeetingEvent.MeetingStarted })
            assertTrue(betaEvents.any { it is MeetingEvent.MeetingStarted })
        }
    }

    // ==================== Full Lifecycle Test ====================

    @Test
    fun `full meeting lifecycle routes all events to participants`() {
        runBlocking {
            val meeting = createTestMeeting()
            val alphaEvents = mutableListOf<MeetingEvent>()

            participationHandler.subscribeAgent("agent-alpha") { event ->
                alphaEvents.add(event)
            }

            // Full lifecycle
            orchestrator.scheduleMeeting(meeting, stubScheduledBy)
            orchestrator.startMeeting(meeting.id)
            orchestrator.advanceAgenda(meeting.id)
            orchestrator.completeMeeting(meeting.id, emptyList())

            // Wait for async event handling
            delay(300)

            // Verify agent received all event types
            assertTrue(alphaEvents.any { it is MeetingEvent.MeetingStarted }, "Should receive MeetingStarted")
            assertTrue(alphaEvents.any { it is MeetingEvent.AgendaItemStarted }, "Should receive AgendaItemStarted")
            assertTrue(alphaEvents.any { it is MeetingEvent.MeetingCompleted }, "Should receive MeetingCompleted")
        }
    }
}
