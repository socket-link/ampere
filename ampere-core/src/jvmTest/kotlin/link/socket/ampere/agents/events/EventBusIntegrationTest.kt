package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusIntegrationTest {

    private val json = DEFAULT_JSON
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        eventRepository = EventRepository(json, scope, Database(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun taskEvent(
        id: String,
        ts: Instant = Clock.System.now(),
    ) = Event.TaskCreated(
        eventId = id,
        urgency = Urgency.LOW,
        timestamp = ts,
        eventSource = EventSource.Agent("agent-A"),
        taskId = "task-123",
        description = "Persisted Task",
        assignedTo = "agent-B",
    )

    private fun questionEvent(
        id: String,
        ts: Instant = Clock.System.now(),
    ) = Event.QuestionRaised(
        eventId = id,
        urgency = Urgency.LOW,
        timestamp = ts,
        eventSource = EventSource.Agent("agent-Q"),
        questionText = "What happened?",
        context = "IntegrationTest",
    )

    @Test
    fun `events persist and can be replayed`() {
        runBlocking {
            val dir = createTempDirectory(prefix = "events-bus-db")
            val dbFile = dir / "bus.sqlite"

            // First bus publishes events
            val driver1 = JdbcSqliteDriver("jdbc:sqlite:$dbFile")
            Database.Schema.create(driver1)
            val bus1 = eventSerialBusFactory.create()

            val e1 = taskEvent("evt-bus-1", ts = Instant.fromEpochSeconds((10_000)))
            val e2 = questionEvent("evt-bus-2", ts = Instant.fromEpochSeconds(20_000))
            val api1 = AgentEventApiFactory(eventRepository, bus1).create("agent-A")
            api1.publish(e1)
            api1.publish(e2)

            // Simulate restart (close driver and re-open)
            driver1.close()

            val driver2 = JdbcSqliteDriver("jdbc:sqlite:$dbFile")
            val bus2 = eventSerialBusFactory.create()
            val api2 = AgentEventApiFactory(eventRepository, bus2).create("agent-A")

            // History query
            val history = api2.getEventHistory()
            assertEquals(2, history.size)
            assertIs<Event.TaskCreated>(history.first { it.eventId == "evt-bus-1" })

            // Subscribe fresh handler and replay events since 0
            val received = CompletableDeferred<List<String>>()
            val acc = mutableListOf<String>()

            bus2.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
                agentId = "agent-A",
                eventType = Event.TaskCreated.EVENT_TYPE,
            ) { event, _ -> acc += event.eventId }

            bus2.subscribe<Event.QuestionRaised, EventSubscription.ByEventClassType>(
                agentId = "agent-B",
                eventType = Event.QuestionRaised.EVENT_TYPE,
            ) { event, _ -> acc += event.eventId }

            api2.replayEvents(since = Instant.fromEpochSeconds(0L))

            withTimeout(2_000) {
                // small delay to allow async handlers to process
                delay(200)
                received.complete(acc)
            }
            val ids = received.await()
            assertTrue(ids.containsAll(listOf("evt-bus-1", "evt-bus-2")))

            driver2.close()
        }
    }

    @Test
    fun `publish failures do not crash bus`() {
        runBlocking {
            val bus = eventSerialBusFactory.create()

            var goodHandlerCalled = false
            bus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
                agentId = "agent-A",
                eventType = Event.TaskCreated.EVENT_TYPE,
            ) { _, _ -> throw IllegalStateException("boom") }

            bus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
                agentId = "agent-A",
                eventType = Event.TaskCreated.EVENT_TYPE,
            ) { _, _ -> goodHandlerCalled = true }

            // Use API to persist then publish
            val api = AgentEventApiFactory(eventRepository, bus).create("agent-A")
            api.publish(taskEvent("evt-crash-1"))

            // allow time for handler execution
            delay(200)
            assertTrue(goodHandlerCalled)
        }
    }

    @Test
    fun `concurrent publishing is safe and persists all events`() {
        runBlocking {
            val bus = eventSerialBusFactory.create()

            val n = 25
            coroutineScope {
                (1..n).map { i ->
                    async {
                        AgentEventApiFactory(eventRepository, bus)
                            .create("agent-A")
                            .publish(taskEvent("evt-conc-$i"))
                    }
                }.awaitAll()
            }

            // Give some time for async dispatch
            delay(200)

            val history = AgentEventApiFactory(eventRepository, bus)
                .create("agent-A")
                .getEventHistory(eventType = Event.TaskCreated.EVENT_TYPE)

            assertEquals(n, history.size)
        }
    }
}
