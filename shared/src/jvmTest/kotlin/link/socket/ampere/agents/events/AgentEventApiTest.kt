package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.api.filterForEventsCreatedByMe
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class AgentEventApiTest {

    private val stubAgentId = "agent-A"
    private val stubAgentId2 = "agent-B"
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
    fun `agent can publish and subscribe to TaskCreated`() {
        runBlocking {
            val api = agentEventApiFactory.create(stubAgentId)

            val received = CompletableDeferred<Event.TaskCreated>()

            val subscription = api.onTaskCreated { event, _ ->
                received.complete(event)
            }

            api.publishTaskCreated(
                taskId = "task-123",
                urgency = Urgency.HIGH,
                description = "Implement feature X",
            )

            val event = received.await()
            assertEquals(stubAgentId, event.eventSource.getIdentifier())
            assertEquals("task-123", event.taskId)
            assertEquals(true, event.eventId.isNotBlank())

            // ** TODO: Test [subscription] can be unsubscribed from. */
        }
    }

    @Test
    fun `multiple subscribers receive same event`() {
        runBlocking {
            val api1 = agentEventApiFactory.create(stubAgentId)
            val api2 = agentEventApiFactory.create(stubAgentId2)

            var c1 = 0
            var c2 = 0

            api1.onTaskCreated { _, _ -> c1++ }
            api2.onTaskCreated { _, _ -> c2++ }
            api1.publishTaskCreated(
                taskId = "t1",
                urgency = Urgency.HIGH,
                description = "desc",
            )

            delay(200)
            assertEquals(1, c1)
            assertEquals(1, c2)
        }
    }

    @Test
    fun `events persist and can be queried historically`() {
        runBlocking {
            val api = agentEventApiFactory.create(stubAgentId)

            val since = Clock.System.now()
            delay(5)
            api.publishQuestionRaised(
                questionText = "Why?",
                context = "test",
                urgency = Urgency.HIGH,
            )

            // allow async persist
            delay(100)

            val events = api.getRecentEvents(since)
            assertEquals(true, events.any { it is Event.QuestionRaised })
        }
    }

    @Test
    fun `multiple AgentEventApi instances can coexist and observe their own agentId's events`() {
        runBlocking {
            val api1 = agentEventApiFactory.create(stubAgentId)
            val receivedA = CompletableDeferred<Event.TaskCreated>()

            val api2 = agentEventApiFactory.create(stubAgentId2)
            val received2 = CompletableDeferred<Event.TaskCreated>()

            val subscription1 = api1.onTaskCreated(
                api1.filterForEventsCreatedByMe(),
            ) { event, _ ->
                receivedA.complete(event)
            }

            val subscription2 = api2.onTaskCreated(
                filter = api2.filterForEventsCreatedByMe(),
            ) { event, _ ->
                received2.complete(event)
            }

            api1.publishTaskCreated("t1", Urgency.HIGH, "from 1")
            api2.publishTaskCreated("t2", Urgency.HIGH, "from 2")

            val e1 = receivedA.await()
            val e2 = received2.await()

            assertEquals(stubAgentId, e1.eventSource.getIdentifier())
            assertEquals(stubAgentId2, e2.eventSource.getIdentifier())
            assertNotEquals(e1.eventId, e2.eventId)

            // ** TODO: Test [subscription1] and [subscription2] can be unsubscribed from. */
        }
    }

    @Test
    fun `code submitted event can be published and subscribed`() {
        runBlocking {
            val api = agentEventApiFactory.create(stubAgentId)
            val received = CompletableDeferred<Event.CodeSubmitted>()

            val subscription = api.onCodeSubmitted { event, _ ->
                received.complete(event)
            }
            api.publishCodeSubmitted(
                urgency = Urgency.HIGH,
                filePath = "/tmp/a.kt",
                changeDescription = "Add feature",
                reviewRequired = true,
            )

            val e = received.await()
            assertIs<Event.CodeSubmitted>(e)
            assertEquals("/tmp/a.kt", e.filePath)
            assertEquals(true, e.reviewRequired)

            // ** TODO: Test [subscription] can be unsubscribed from. */
        }
    }
}
