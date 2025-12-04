package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class AgentEventBusIntegrationTest {

    private val json = DEFAULT_JSON
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var agentEventApiFactory: AgentEventApiFactory

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)

        eventRepository = EventRepository(json, scope, database)
        eventSerialBus = eventSerialBusFactory.create()
        agentEventApiFactory = AgentEventApiFactory(eventRepository, eventSerialBus)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `complete agent communication flow`() {
        runBlocking {
            // Setup: Three agents with one shared event bus
            val api1 = agentEventApiFactory.create("code-writer")
            val api2 = agentEventApiFactory.create("code-reviewer")
            val api3 = agentEventApiFactory.create("task-manager")

            // Code writer subscribes to tasks before any are published
            val received = CompletableDeferred<Event.TaskCreated>()
            api1.onTaskCreated { event, _ -> received.complete(event) }

            // Scenario: Task manager creates task (after subscription is in place)
            api3.publishTaskCreated(
                taskId = "TASK-001",
                urgency = Urgency.HIGH,
                description = "Implement event bus",
                assignedTo = "code-writer",
            )

            val event = received.await()
            assertEquals("code-writer", event.assignedTo)
            assertEquals("TASK-001", event.taskId)
            assertEquals(true, event.eventId.isNotBlank())

            // Code reviewer subscribes before code is submitted
            val codeSubmissions = mutableListOf<Event.CodeSubmitted>()
            api2.onCodeSubmitted { event, _ -> codeSubmissions.add(event) }

            // Code writer submits code (after reviewer subscription)
            api1.publishCodeSubmitted(
                urgency = Urgency.HIGH,
                filePath = "EventBus.kt",
                changeDescription = "Initial implementation",
                reviewRequired = true,
            )

            delay(100)
            assertEquals(1, codeSubmissions.size)

            // Reviewer raises question
            api2.publishQuestionRaised(
                questionText = "Should we add error handling?",
                context = "Reviewing EventBus.kt",
                urgency = Urgency.HIGH,
            )

            // Verify all events are in history
            val allEvents = api1.getRecentEvents(since = null)
            assertEquals(3, allEvents.size)
            assertEquals(true, allEvents.any { it is Event.TaskCreated })
            assertEquals(true, allEvents.any { it is Event.CodeSubmitted })
            assertEquals(true, allEvents.any { it is Event.QuestionRaised })
        }
    }
}
