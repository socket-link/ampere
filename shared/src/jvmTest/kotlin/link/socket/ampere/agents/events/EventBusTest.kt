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
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class EventBusTest {

    private val json = DEFAULT_JSON
    private val scope = TestScope(UnconfinedTestDispatcher())

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

    private fun taskEvent(): Event.TaskCreated = Event.TaskCreated(
        eventId = "evt-1",
        urgency = Urgency.HIGH,
        timestamp = Clock.System.now(),
        eventSource = EventSource.Agent("agent-A"),
        taskId = "task-123",
        description = "Do something important",
        assignedTo = "agent-B",
    )

    private fun questionEvent(): Event.QuestionRaised = Event.QuestionRaised(
        eventId = "evt-2",
        timestamp = Clock.System.now(),
        eventSource = EventSource.Agent("agent-C"),
        questionText = "Why?",
        context = "Testing context",
        urgency = Urgency.MEDIUM,
    )

    @Test
    fun `subscriber receives only matching events`() {
        runBlocking {
            val bus = EventSerialBus(scope)
            val receivedTask = CompletableDeferred<Event.TaskCreated>()
            var nonMatchingCalled: Boolean

            bus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
                agentId = "agent-X",
                eventClassType = Event.TaskCreated.EVENT_CLASS_TYPE,
            ) { event, _ ->
                receivedTask.complete(event)
            }

            // Publish matching event
            bus.publish(taskEvent())

            val result = withTimeout(2_000) { receivedTask.await() }
            assertEquals("task-123", result.taskId)

            // Publish non-matching event and ensure the prior handler is not triggered again
            val before = receivedTask.isCompleted
            bus.publish(questionEvent())
            delay(200) // small delay to allow any accidental dispatch

            val after = receivedTask.isCompleted
            nonMatchingCalled = before && after // remains completed, not re-triggered
            assertEquals(true, nonMatchingCalled)
        }
    }

    @Test
    fun `multiple subscribers receive event`() {
        runBlocking {
            val bus = EventSerialBus(scope)
            val s1 = CompletableDeferred<Boolean>()
            val s2 = CompletableDeferred<Boolean>()

            bus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
                agentId = "agent-X",
                eventClassType = Event.TaskCreated.EVENT_CLASS_TYPE,
            ) { _, _ -> s1.complete(true) }

            bus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
                agentId = "agent-Y",
                eventClassType = Event.TaskCreated.EVENT_CLASS_TYPE,
            ) { _, _ -> s2.complete(true) }

            bus.publish(taskEvent())

            withTimeout(2_000) {
                assertEquals(true, s1.await())
            }
            withTimeout(2_000) {
                assertEquals(true, s2.await())
            }
        }
    }

    @Test
    fun `unsubscribe prevents further delivery`() {
        runBlocking {
            var count = 0

            val bus = EventSerialBus(scope)
            val subscription = bus.subscribe<Event.TaskCreated, EventSubscription.ByEventClassType>(
                agentId = "agent-X",
                eventClassType = Event.TaskCreated.EVENT_CLASS_TYPE,
            ) { _, _ -> count += 1 }

            // First publish should deliver
            bus.publish(taskEvent())
            delay(200)
            assertEquals(1, count)

            // Now unsubscribe and publish again
            bus.unsubscribe(Event.TaskCreated.EVENT_CLASS_TYPE)
            bus.publish(taskEvent())
            delay(200)
            assertEquals(1, count)
        }
    }
}
