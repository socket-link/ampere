package link.socket.ampere.agents.events.messages

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.type.AgentId
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.escalation.EscalationEventHandler
import link.socket.ampere.agents.events.escalation.Notifier
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRouterTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var eventRepository: EventRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var agentMessageApiFactory: AgentMessageApiFactory
    private lateinit var agentEventApiFactory: AgentEventApiFactory

    // TODO: Test this functionality
    private val fakeHumanNotifier = object : Notifier.Human() {
        override suspend fun notifyEscalation(
            threadId: MessageThreadId,
            agentId: AgentId,
            reason: String,
            context: Map<String, String>?,
        ) {
            TODO("Not yet implemented")
        }
    }

    private lateinit var escalationEventHandler: EscalationEventHandler

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, database)
        messageRepository = MessageRepository(DEFAULT_JSON, scope, database)
        eventSerialBus = eventSerialBusFactory.create()
        agentMessageApiFactory = AgentMessageApiFactory(messageRepository, eventSerialBus)
        agentEventApiFactory = AgentEventApiFactory(eventRepository, eventSerialBus)
        escalationEventHandler = EscalationEventHandler(scope, fakeHumanNotifier, eventSerialBus)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `routes thread and channel events to subscribed agents`() {
        runBlocking {
            val routerApi = agentMessageApiFactory.create("router-agent")
            val router = MessageRouter(routerApi, escalationEventHandler, eventSerialBus)

            val targetAgent = "agent-subscriber"
            val channel = MessageChannel.Public.Engineering
            router.subscribeToChannel(targetAgent, channel)

            // Capture notifications to agents
            val notifications = mutableListOf<NotificationEvent.ToAgent<*>>()
            eventSerialBus.subscribe<NotificationEvent.ToAgent<*>, Subscription>(
                agentId = "observer",
                eventType = NotificationEvent.ToAgent.EVENT_TYPE,
            ) { event, _ ->
                notifications += event
            }

            router.startRouting()

            // Use a producer to create a thread in the channel and post a follow-up message
            val producer = agentMessageApiFactory.create("producer-A")
            val thread = producer.createThread(
                participants = setOf("someone"),
                channel = channel,
                initialMessageContent = "Kickoff",
            )

            // Post a message in the same thread to trigger channel message posted routing
            producer.postMessage(thread.id, "Follow-up")

            // Allow async dispatch
            delay(250)

            // At least two notifications: thread created and message posted
            assertTrue(notifications.size >= 2)
            // All notifications should target the subscribed agent
            assertTrue(notifications.all { (it.eventSource as EventSource.Agent).agentId == targetAgent })
            // Ensure we have at least one notification for a thread-related event
            assertTrue(notifications.any { it.event.eventType == MessageEvent.ThreadCreated.EVENT_TYPE })
            // And one for a message posted in the channel
            assertTrue(notifications.any { it.event.eventType == MessageEvent.MessagePosted.EVENT_TYPE })
        }
    }
}
