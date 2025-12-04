package link.socket.ampere.agents.events

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.subscription.EventSubscription
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class EventSubscriptionTest {

    private val agentId = "agent-1"
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var eventRepository: EventRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        eventRepository = EventRepository(link.socket.ampere.data.DEFAULT_JSON, scope, database)
        eventSerialBus = eventSerialBusFactory.create()
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `subscription id contains agent id and event type`() {
        val sub = EventSubscription.ByEventClassType(
            agentIdOverride = agentId,
            eventTypes = setOf(Event.TaskCreated.EVENT_TYPE),
        )

        // Format includes a joined representation of types, then "/$agentId"
        assertTrue(sub.subscriptionId.endsWith("/$agentId"))
        assertTrue(sub.subscriptionId.contains("TaskCreated"))
    }

    @Test
    fun `event router merge and unsubscribe semantics`() {
        val api = AgentEventApiFactory(eventRepository, eventSerialBus).create(agentId)
        val router = EventRouter(api, eventSerialBus)

        // Subscribe to TaskCreated, then to QuestionRaised
        router.subscribeToEventClassType(agentId, Event.TaskCreated.EVENT_TYPE)
        val s2 = router.subscribeToEventClassType(agentId, Event.QuestionRaised.EVENT_TYPE)

        // Same agent, subscription should accumulate both types
        assertTrue(Event.TaskCreated.EVENT_TYPE in s2.eventTypes)
        assertTrue(Event.QuestionRaised.EVENT_TYPE in s2.eventTypes)

        // Unsubscribe from one (call extension within router scope)
        val s3 = router.run { s2.unsubscribeFromEventClassType(Event.TaskCreated.EVENT_TYPE) }
        assertTrue(Event.QuestionRaised.EVENT_TYPE in s3.eventTypes)
        assertTrue(Event.TaskCreated.EVENT_TYPE !in s3.eventTypes)

        // getSubscribedAgentsFor should reflect current mapping
        val agentsForQuestion = router.getSubscribedAgentsFor(Event.QuestionRaised.EVENT_TYPE)
        assertEquals(listOf(agentId), agentsForQuestion)
        val agentsForTask = router.getSubscribedAgentsFor(Event.TaskCreated.EVENT_TYPE)
        assertEquals(emptyList(), agentsForTask)
    }
}
