package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventStoreEvent
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.subscription.EventSubscription
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
        val router = EventRouter(routerApi)

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

        // F1: the notification went through the door and is in the EventStore
        val storedNotifications = eventRepository
            .getEventsByType(NotificationEvent.ToAgent.EVENT_TYPE)
            .getOrThrow()
        assertEquals(1, storedNotifications.size)
        assertEquals(n.eventId, storedNotifications.single().eventId)

        // F2: it is caused by the TaskCreated it routed
        val taskCreated = eventRepository.getEventsByType(Event.TaskCreated.EVENT_TYPE).getOrThrow().single()
        val causedByTask = eventRepository.getEventsCausedBy(taskCreated.eventId).getOrThrow()
        assertEquals(listOf(n.eventId), causedByTask.map { it.event.eventId })
    }

    @Test
    fun `routes to an agent registered after startRouting`() = runBlocking {
        // The order every real consumer gets: EnvironmentOrchestrator.start() runs at boot,
        // and the agent that wants notifications registers later (AMPR-404).
        val router = EventRouter(agentEventApiFactory.create("router-agent"))
        val notifications = collectNotifications()

        router.startRouting()
        router.subscribeToEventClassType("late-agent", Event.TaskCreated.EVENT_TYPE)

        publishTaskCreated("task-late")
        delay(DISPATCH_WINDOW_MS)

        assertEquals(listOf("late-agent"), notifications.map { it.agentId })
    }

    @Test
    fun `notifies every agent registered for the type`() = runBlocking {
        val router = EventRouter(agentEventApiFactory.create("router-agent"))
        val notifications = collectNotifications()

        router.subscribeToEventClassType("agent-a", Event.TaskCreated.EVENT_TYPE)
        router.subscribeToEventClassType("agent-b", Event.TaskCreated.EVENT_TYPE)
        router.subscribeToEventClassType("agent-c", Event.QuestionRaised.EVENT_TYPE)
        router.startRouting()

        publishTaskCreated("task-fanout")
        delay(DISPATCH_WINDOW_MS)

        // agent-c asked for a different type and is not notified of this one.
        assertEquals(setOf("agent-a", "agent-b"), notifications.map { it.agentId }.toSet())
    }

    @Test
    fun `a notification carries the recipient's own routing subscription`() = runBlocking {
        val router = EventRouter(agentEventApiFactory.create("router-agent"))
        val notifications = collectNotifications()

        router.subscribeToEventClassType("agent-a", Event.TaskCreated.EVENT_TYPE)
        router.subscribeToEventClassType("agent-a", Event.QuestionRaised.EVENT_TYPE)
        router.startRouting()

        publishTaskCreated("task-subscription")
        delay(DISPATCH_WINDOW_MS)

        val subscription = assertIs<EventSubscription.ByEventClassType>(
            notifications.single().subscription,
        )
        assertEquals("agent-a", subscription.agentId)
        assertEquals(
            setOf(Event.TaskCreated.EVENT_TYPE, Event.QuestionRaised.EVENT_TYPE),
            subscription.eventTypes,
        )
    }

    @Test
    fun `startRouting is idempotent`() = runBlocking {
        val router = EventRouter(agentEventApiFactory.create("router-agent"))
        val notifications = collectNotifications()
        val persistenceFailures = mutableListOf<EventStoreEvent.PersistenceFailed>()
        eventSerialBus.subscribe<EventStoreEvent.PersistenceFailed, Subscription>(
            agentId = "observer-failures",
            eventType = EventStoreEvent.PersistenceFailed.EVENT_TYPE,
        ) { event, _ -> persistenceFailures += event }

        router.subscribeToEventClassType("agent-a", Event.TaskCreated.EVENT_TYPE)
        router.startRouting()
        router.startRouting()

        publishTaskCreated("task-idempotent")
        delay(DISPATCH_WINDOW_MS)

        // A second handler on the same type would derive the same NotificationEvent.ToAgent id
        // and collide on the primary key, so a duplicate registration surfaces as a persistence
        // failure rather than as a second notification. Assert on both.
        assertEquals(1, notifications.size)
        assertEquals(emptyList(), persistenceFailures)
    }

    @Test
    fun `stopRouting releases the handlers and startRouting resumes`() = runBlocking {
        val router = EventRouter(agentEventApiFactory.create("router-agent"))
        val notifications = collectNotifications()

        router.subscribeToEventClassType("agent-a", Event.TaskCreated.EVENT_TYPE)
        router.startRouting()
        assertTrue(router.isRouting)

        router.stopRouting()
        assertFalse(router.isRouting)
        publishTaskCreated("task-while-stopped")
        delay(DISPATCH_WINDOW_MS)
        assertEquals(emptyList(), notifications.map { it.agentId })

        // Registrations survive a stop, so resuming needs no re-registration.
        router.startRouting()
        publishTaskCreated("task-after-restart")
        delay(DISPATCH_WINDOW_MS)
        assertEquals(listOf("agent-a"), notifications.map { it.agentId })
    }

    @Test
    fun `unsubscribing one agent leaves the others notified`() = runBlocking {
        val router = EventRouter(agentEventApiFactory.create("router-agent"))
        val notifications = collectNotifications()

        router.subscribeToEventClassType("agent-a", Event.TaskCreated.EVENT_TYPE)
        router.subscribeToEventClassType("agent-b", Event.TaskCreated.EVENT_TYPE)
        router.startRouting()

        router.unsubscribeFromEventClassType("agent-a", Event.TaskCreated.EVENT_TYPE)

        // Its last type gone, agent-a leaves the registry rather than lingering with an empty set.
        assertEquals(listOf("agent-b"), router.getSubscribedAgentsFor(Event.TaskCreated.EVENT_TYPE))

        publishTaskCreated("task-after-unsubscribe")
        delay(DISPATCH_WINDOW_MS)

        assertEquals(listOf("agent-b"), notifications.map { it.agentId })
    }

    /** Capture every [NotificationEvent.ToAgent] the router publishes, in dispatch order. */
    private fun collectNotifications(): MutableList<NotificationEvent.ToAgent<*>> {
        val notifications = mutableListOf<NotificationEvent.ToAgent<*>>()

        eventSerialBus.subscribe<NotificationEvent.ToAgent<*>, Subscription>(
            agentId = "observer",
            eventType = NotificationEvent.ToAgent.EVENT_TYPE,
        ) { event, _ ->
            notifications += event
        }

        return notifications
    }

    private suspend fun publishTaskCreated(taskId: String) {
        agentEventApiFactory.create("producer-A").publishTaskCreated(
            taskId = taskId,
            urgency = Urgency.HIGH,
            description = "desc",
        )
    }

    private companion object {
        /** Long enough for the door's write plus the bus's asynchronous dispatch. */
        const val DISPATCH_WINDOW_MS = 200L
    }
}
