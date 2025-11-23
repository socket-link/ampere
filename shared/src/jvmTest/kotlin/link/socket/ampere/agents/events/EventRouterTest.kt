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
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventBus
import link.socket.ampere.agents.events.bus.EventBusFactory
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class EventRouterTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventBusFactory = EventBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventBus: EventBus
    private lateinit var eventRepository: EventRepository
    private lateinit var agentEventApiFactory: AgentEventApiFactory

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        eventRepository = EventRepository(link.socket.ampere.data.DEFAULT_JSON, scope, database)
        eventBus = eventBusFactory.create()
        agentEventApiFactory = AgentEventApiFactory(eventRepository, eventBus)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `routes TaskCreated to subscribed agents as NotificationEvent`() = runBlocking {
        val routerApi = agentEventApiFactory.create("router-agent")
        val router = EventRouter(routerApi, eventBus)

        val targetAgent = "agent-b"
        router.subscribeToEventClassType(targetAgent, Event.TaskCreated.EVENT_CLASS_TYPE)

        // Capture notifications to agents
        var notifications = mutableListOf<NotificationEvent.ToAgent<*>>()
        eventBus.subscribe<NotificationEvent.ToAgent<*>, Subscription>(
            agentId = "observer",
            eventClassType = NotificationEvent.ToAgent.EVENT_CLASS_TYPE,
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
        assertEquals(Event.TaskCreated.EVENT_CLASS_TYPE, (n.event as Event).eventClassType)
    }
}
