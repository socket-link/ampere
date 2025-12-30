package link.socket.ampere.agents.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.ProductAgent
import link.socket.ampere.agents.definition.QualityAgent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.meetings.MeetingOrchestrator
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.meetings.MeetingSchedulingService
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.tickets.TicketOrchestrator
import link.socket.ampere.agents.events.tickets.TicketRepository
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.stubFailureOutcome
import link.socket.ampere.stubKnowledgeEntry
import link.socket.ampere.stubProductManagerAgent
import link.socket.ampere.stubQualityAssuranceAgent
import link.socket.ampere.stubSuccessOutcome

/**
 * Integration tests for the full agent learning loop.
 */
class AgentLearningLoopIntegrationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    private lateinit var messageRepository: MessageRepository
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var ticketRepository: TicketRepository

    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var messageApi: AgentMessageApi
    private lateinit var meetingOrchestrator: MeetingOrchestrator
    private lateinit var ticketOrchestrator: TicketOrchestrator

    private lateinit var productAgent: ProductAgent
    private lateinit var qualityAgent: QualityAgent

    private val testScope = CoroutineScope(Dispatchers.Default)
    private val stubOrchestratorAgentId: AgentId = "orchestrator-agent"

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

        productAgent = stubProductManagerAgent(
            ticketOrchestrator = ticketOrchestrator,
        )

        qualityAgent = stubQualityAssuranceAgent()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `ProductManagerAgent learning loop - second task benefits from first`() = runBlocking {
        // First task execution
        val firstTask = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Implement authentication feature",
        )

        val firstPlan = productAgent.determinePlanForTask(firstTask, relevantKnowledge = emptyList())
        val firstOutcome = stubSuccessOutcome()

        // Extract knowledge from first execution
        val extractedKnowledge = productAgent.extractKnowledgeFromOutcome(
            firstOutcome,
            firstTask,
            Plan.ForTask(
                task = firstTask,
                tasks = listOf(
                    Task.CodeChange(
                        id = "subtask-1",
                        status = TaskStatus.Pending,
                        description = "Define test specifications",
                    ),
                ),
            ),
        )

        assertTrue(extractedKnowledge.approach.isNotEmpty())
        assertTrue(extractedKnowledge.learnings.contains("Success"))

        // Second task execution with recalled knowledge
        val secondTask = Task.CodeChange(
            id = "task-2",
            status = TaskStatus.Pending,
            description = "Implement authorization feature",
        )

        val recalledKnowledge = listOf(
            KnowledgeWithScore(
                entry = stubKnowledgeEntry(
                    id = "k1",
                    approach = extractedKnowledge.approach,
                    learnings = extractedKnowledge.learnings,
                    outcomeId = firstOutcome.id,
                    tags = listOf("success"),
                    taskType = "code_change",
                ),
                knowledge = extractedKnowledge,
                relevanceScore = 0.9,
            ),
        )

        val secondPlan = productAgent.determinePlanForTask(secondTask, relevantKnowledge = recalledKnowledge)

        assertTrue(secondPlan.tasks.isNotEmpty())
    }

    @Test
    fun `Agents handle cold start with no knowledge`() = runBlocking {
        val task = Task.CodeChange(
            id = "cold-start-task",
            status = TaskStatus.Pending,
            description = "Implement new feature",
        )

        val pmPlan = productAgent.determinePlanForTask(task, relevantKnowledge = emptyList())
        val validationPlan = qualityAgent.determinePlanForTask(task, relevantKnowledge = emptyList())

        assertTrue(pmPlan.tasks.isNotEmpty(), "PM should create plan without knowledge")
        assertTrue(validationPlan.tasks.isNotEmpty(), "Validation should create plan without knowledge")
    }

    @Test
    fun `Extracted knowledge structure is consistent`() {
        val task = Task.CodeChange(
            id = "consistency-task",
            status = TaskStatus.Pending,
            description = "Test task",
        )

        val plan = Plan.ForTask(task = task)

        // Extract from success
        val successOutcome = stubSuccessOutcome()
        val successKnowledge = productAgent.extractKnowledgeFromOutcome(successOutcome, task, plan)

        // Extract from failure
        val failureOutcome = stubFailureOutcome()
        val failureKnowledge = productAgent.extractKnowledgeFromOutcome(failureOutcome, task, plan)

        assertTrue(successKnowledge is Knowledge.FromOutcome)
        assertTrue(failureKnowledge is Knowledge.FromOutcome)
        assertTrue(successKnowledge.approach.isNotEmpty())
        assertTrue(failureKnowledge.approach.isNotEmpty())
        assertTrue(successKnowledge.learnings.contains("Success"))
        assertTrue(failureKnowledge.learnings.contains("Failure"))
    }
}
