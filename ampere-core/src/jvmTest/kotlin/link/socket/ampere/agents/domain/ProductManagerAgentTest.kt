package link.socket.ampere.agents.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.ProductManagerAgent
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
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
import link.socket.ampere.stubKnowledgeEntry
import link.socket.ampere.stubProductManagerAgent
import link.socket.ampere.stubSuccessOutcome

class ProductManagerAgentTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    private lateinit var messageRepository: MessageRepository
    private lateinit var meetingRepository: MeetingRepository
    private lateinit var ticketRepository: TicketRepository

    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var messageApi: AgentMessageApi
    private lateinit var meetingOrchestrator: MeetingOrchestrator
    private lateinit var ticketOrchestrator: TicketOrchestrator

    private lateinit var productManagerAgent: ProductManagerAgent

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

        productManagerAgent = stubProductManagerAgent(
            ticketOrchestrator = ticketOrchestrator,
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `determinePlanForTask creates plan with knowledge`() = runBlocking {
        val testFirstKnowledge = listOf(
            KnowledgeWithScore(
                entry = stubKnowledgeEntry(
                    id = "k1",
                    approach = "Used test-first approach with 3 tasks",
                    learnings = "Test-first approach prevented issues in authentication flow",
                    outcomeId = "outcome-1",
                    tags = listOf("success", "test"),
                    taskType = "code_change",
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-1",
                    approach = "Used test-first approach with 3 tasks",
                    learnings = "Test-first approach prevented issues in authentication flow",
                    timestamp = Clock.System.now(),
                ),
                relevanceScore = 0.9,
            ),
        )

        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Implement user profile feature",
        )

        val plan = productManagerAgent.determinePlanForTask(task, relevantKnowledge = testFirstKnowledge)

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include tasks")
    }

    @Test
    fun `extractKnowledgeFromOutcome captures success learnings`() {
        val task = Task.CodeChange(
            id = "task-4",
            status = TaskStatus.Pending,
            description = "Implement authentication system",
        )

        val plan = Plan.ForTask(task = task)
        val outcome = stubSuccessOutcome()

        val knowledge = productManagerAgent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge.approach.isNotEmpty(), "Approach should be captured")
        assertTrue(knowledge.learnings.contains("Success"), "Learnings should mention success")
        assertTrue(knowledge is Knowledge.FromOutcome)
    }

    @Test
    fun `determinePlanForTask handles empty knowledge`() = runBlocking {
        val task = Task.CodeChange(
            id = "task-6",
            status = TaskStatus.Pending,
            description = "Implement new feature",
        )

        val plan = productManagerAgent.determinePlanForTask(task = task, relevantKnowledge = emptyList())

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include tasks even without knowledge")
    }
}
