package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class EventRouterTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var eventRepository: EventRepository
    private lateinit var agentEventApiFactory: AgentEventApiFactory

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        eventRepository = EventRepository(link.socket.ampere.data.DEFAULT_JSON, scope, database)
        eventSerialBus = eventSerialBusFactory.create()
        agentEventApiFactory = AgentEventApiFactory(eventRepository, eventSerialBus)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `routes TaskCreated to subscribed agents as NotificationEvent`() = runBlocking {
        val routerApi = agentEventApiFactory.create("router-agent")
        val router = EventRouter(routerApi, eventSerialBus)

        val targetAgent = "agent-b"
        router.subscribeToEventClassType(targetAgent, Event.TaskCreated.EVENT_TYPE)

        // Capture notifications to agents
        var notifications = mutableListOf<NotificationEvent.ToAgent<*>>()
        eventSerialBus.subscribe<NotificationEvent.ToAgent<*>, Subscription>(
            agentId = "observer",
            eventType = NotificationEvent.ToAgent.EVENT_TYPE,
        ) { event, _ ->
            notifications += event
        }

        // Start routing after subscriptions are in place
        router.startRouting()

        // Publish a TaskCreated from another agent
        val producer = agentEventApiFactory.create("producer-A")
        producer.publishTaskCreated(
            taskId = "task-1",
            urgency = Urgency.HIGH,
            description = "desc",
        )

        // Allow async dispatch
        delay(200)

        assertEquals(1, notifications.size)
        val n = notifications.first()
        assertIs<NotificationEvent.ToAgent<*>>(n)
        assertEquals(targetAgent, (n.eventSource as EventSource.Agent).agentId)
        assertEquals(Event.TaskCreated.EVENT_TYPE, n.event.eventType)
    }
}
