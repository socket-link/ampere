package link.socket.ampere.agents.events.messages

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.bus.subscribe
import link.socket.ampere.agents.events.escalation.EscalationEventHandler
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

    private lateinit var escalationEventHandler: EscalationEventHandler

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, database)
        messageRepository = MessageRepository(DEFAULT_JSON, scope, database)
        eventSerialBus = eventSerialBusFactory.create()
        agentEventApiFactory = AgentEventApiFactory(eventRepository, eventSerialBus)
        agentMessageApiFactory = AgentMessageApiFactory(messageRepository, agentEventApiFactory)
        escalationEventHandler = EscalationEventHandler(scope, eventSerialBus)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `routes thread and channel events to subscribed agents`() {
        runBlocking {
            val routerApi = agentMessageApiFactory.create("router-agent")
            val router = MessageRouter(
                messageApi = routerApi,
                escalationEventHandler = escalationEventHandler,
                eventApi = agentEventApiFactory.create("router-agent"),
            )

            val targetAgent = "agent-subscriber"
            val channel = MessageChannel.Public.Engineering
            router.subscribeToChannel(targetAgent, channel)

            // Capture notifications to agents. The handler runs on whatever thread the door
            // dispatches from, so every access goes through the list's own lock.
            val captured = mutableListOf<NotificationEvent.ToAgent<*>>()
            eventSerialBus.subscribe<NotificationEvent.ToAgent<*>, Subscription>(
                agentId = "observer",
                eventType = NotificationEvent.ToAgent.EVENT_TYPE,
            ) { event, _ ->
                synchronized(captured) { captured += event }
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

            // Dispatch is asynchronous, and the door persists before it publishes. A fixed
            // sleep loses that race on a loaded runner, so wait for routing to go quiet.
            awaitSettled {
                val seen = synchronized(captured) { captured.toList() }
                val stored = eventRepository
                    .getEventsByType(NotificationEvent.ToAgent.EVENT_TYPE)
                    .getOrThrow()
                    .size
                val routedBoth = seen.any { it.event.eventType == MessageEvent.ThreadCreated.EVENT_TYPE } &&
                    seen.any { it.event.eventType == MessageEvent.MessagePosted.EVENT_TYPE }

                if (routedBoth && stored == seen.size) seen.size else null
            }
            val notifications = synchronized(captured) { captured.toList() }

            // At least two notifications: thread created and message posted
            assertTrue(notifications.size >= 2)
            // All notifications should target the subscribed agent
            assertTrue(notifications.all { (it.eventSource as EventSource.Agent).agentId == targetAgent })
            // Ensure we have at least one notification for a thread-related event
            assertTrue(notifications.any { it.event.eventType == MessageEvent.ThreadCreated.EVENT_TYPE })
            // And one for a message posted in the channel
            assertTrue(notifications.any { it.event.eventType == MessageEvent.MessagePosted.EVENT_TYPE })

            // F1: every notification went through the door and is in the EventStore
            val storedNotifications = eventRepository
                .getEventsByType(NotificationEvent.ToAgent.EVENT_TYPE)
                .getOrThrow()
            assertEquals(notifications.size, storedNotifications.size)

            // F2: the thread-created notification is caused by the ThreadCreated it routed
            val threadCreated = eventRepository
                .getEventsByType(MessageEvent.ThreadCreated.EVENT_TYPE)
                .getOrThrow()
                .single()
            val causedByThreadCreated = eventRepository.getEventsCausedBy(threadCreated.eventId).getOrThrow()
            assertTrue(
                causedByThreadCreated.any { it.event.eventType == NotificationEvent.ToAgent.EVENT_TYPE },
                "caused: ${causedByThreadCreated.map { it.event.eventType }}",
            )
        }
    }

    /**
     * Polls [settledCount] until it has returned the same non-null value for [quiet], or
     * [timeout] passes. Returning on timeout rather than throwing leaves the failure to the
     * assertions that follow, which say what is actually missing.
     */
    private suspend fun awaitSettled(
        timeout: Duration = 10.seconds,
        quiet: Duration = 250.milliseconds,
        settledCount: suspend () -> Int?,
    ) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var last: Int? = null
        var quietSince = TimeSource.Monotonic.markNow()

        while (deadline.hasNotPassedNow()) {
            val count = settledCount()
            if (count == null || count != last) {
                last = count
                quietSince = TimeSource.Monotonic.markNow()
            } else if (quietSince.elapsedNow() >= quiet) {
                return
            }
            delay(25)
        }
    }
}
