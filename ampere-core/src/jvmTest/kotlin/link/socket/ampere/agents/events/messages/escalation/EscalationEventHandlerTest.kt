package link.socket.ampere.agents.events.messages.escalation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.escalation.EscalationEventHandler
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.AgentMessageApiFactory
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.messages.MessageRouter
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EscalationEventHandlerTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var eventRepository: EventRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var apiFactory: AgentMessageApiFactory
    private lateinit var eventHandler: EscalationEventHandler

    private var lastEscalationEvent: MessageEvent.EscalationRequested? = null

    private fun getMessageRouter(api: AgentMessageApi) = MessageRouter(
        messageApi = api,
        escalationEventHandler = eventHandler,
        eventSerialBus = eventSerialBus,
    )

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, database)
        messageRepository = MessageRepository(DEFAULT_JSON, scope, database)
        eventSerialBus = eventSerialBusFactory.create()
        apiFactory = AgentMessageApiFactory(messageRepository, eventSerialBus)
        eventHandler = EscalationEventHandler(scope, eventSerialBus)
    }

    @AfterTest
    fun tearDown() {
        lastEscalationEvent = null
        driver.close()
    }

    @Test
    fun `EscalationRequested is published to bus when escalateToHuman is called`() {
        runBlocking {
            val agentId = "test-agent"
            val api: AgentMessageApi = apiFactory.create(agentId)

            val thread: MessageThread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Hello",
            )

            // Subscribe to capture the event
            eventSerialBus.subscribe(
                agentId = "test-subscriber",
                eventType = MessageEvent.EscalationRequested.EVENT_TYPE,
                handler = link.socket.ampere.agents.events.api.EventHandler { event, _ ->
                    lastEscalationEvent = event as MessageEvent.EscalationRequested
                },
            )

            val reason = "Need human approval"
            val ctx = mapOf("key" to "value")

            // escalateToHuman suspends waiting for a reply; we don't await it in this test
            // because we only care that EscalationRequested was published.
            val job = GlobalScope.launch {
                api.escalateToHuman(threadId = thread.id, reason = reason, context = ctx)
            }

            delay(200.milliseconds)

            val event = lastEscalationEvent
            assertNotNull(event)
            assertEquals(thread.id, event.threadId)
            assertEquals(reason, event.reason)
            assertEquals(ctx, event.context)

            job.cancel()
        }
    }

    @Test
    fun `escalation handler start method self-subscribes without error`() {
        runBlocking {
            eventHandler.start()
            delay(100.milliseconds)
            // No assertion needed — just verifying start() does not throw.
            assertNull(lastEscalationEvent)
        }
    }

    @Test
    fun `EscalationEventHandler no longer invokes humanNotifier`() {
        // humanNotifier is no longer part of EscalationEventHandler — notification routing
        // is handled by SurfacePolicy via the Emission DSL in AgentMessageApi.escalateToHuman.
        // This test documents the removal as a structural assertion: the handler compiles
        // without a Notifier.Human parameter.
        val handler = EscalationEventHandler(
            coroutineScope = scope,
            eventSerialBus = eventSerialBus,
        )
        assertNotNull(handler) // handler constructed without humanNotifier
    }
}
