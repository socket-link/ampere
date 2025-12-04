package link.socket.ampere.agents.events.messages.escalation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.escalation.EscalationEventHandler
import link.socket.ampere.agents.events.escalation.Notifier
import link.socket.ampere.agents.events.messages.AgentMessageApi
import link.socket.ampere.agents.events.messages.AgentMessageApiFactory
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.messages.MessageRouter
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.util.randomUUID

@OptIn(ExperimentalCoroutinesApi::class)
class EscalationEventHandlerTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var eventRepository: EventRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var apiFactory: AgentMessageApiFactory

    private class FakeHumanNotifier : Notifier.Human() {

        data class Call(
            val threadId: MessageThreadId,
            val agentId: String,
            val reason: String,
            val context: Map<String, String>?,
        )

        var lastCall: Call? = null

        override suspend fun notifyEscalation(
            threadId: MessageThreadId,
            agentId: String,
            reason: String,
            context: Map<String, String>?,
        ) {
            lastCall = Call(threadId, agentId, reason, context)
        }
    }

    private val humanNotifier = FakeHumanNotifier()
    private lateinit var eventHandler: EscalationEventHandler

    private fun getMessageRouter(
        api: AgentMessageApi,
    ) = MessageRouter(
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
        eventHandler = EscalationEventHandler(scope, humanNotifier, eventSerialBus)
    }

    @AfterTest
    fun tearDown() {
        humanNotifier.lastCall = null
        driver.close()
    }

    @Test
    fun `notifier reacts to escalation event and forwards to human`() {
        runBlocking {
            val agentId = "notifier-agent"
            val api: AgentMessageApi = apiFactory.create(agentId)
            val messageRouter = getMessageRouter(api)
            messageRouter.startRouting()

            val thread: MessageThread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Hello",
            )

            val reason = "Need human approval"
            val ctx = mapOf("key" to "value")

            messageRouter.subscribeToMessageType(
                agentId = agentId,
                eventType = MessageEvent.EscalationRequested.EVENT_TYPE,
            )

            api.escalateToHuman(
                threadId = thread.id,
                reason = reason,
                context = ctx,
            )

            // let async handlers run
            delay(200)

            val call = humanNotifier.lastCall
            assertNotNull(call)
            assertEquals(thread.id, call.threadId)
            assertEquals(agentId, call.agentId)
            assertEquals(reason, call.reason)
            assertEquals(ctx, call.context)
        }
    }

    @Test
    fun `no thread found leads to no human notification`() {
        runBlocking {
            val agentId = "notifier-agent"
            val api: AgentMessageApi = apiFactory.create(agentId)
            val messageRouter = getMessageRouter(api)
            messageRouter.startRouting()

            // Publish an escalation for a non-existent threadId
            val fakeThreadId = randomUUID()

            messageRouter.subscribeToMessageType(
                agentId = agentId,
                eventType = MessageEvent.EscalationRequested.EVENT_TYPE,
            )

            api.escalateToHuman(
                threadId = fakeThreadId,
                reason = "Missing thread",
            )

            // let async handlers run; exceptions inside handler are swallowed by EventBus
            delay(200)

            // Since the thread lookup fails, the notifier should not be called
            assertNull(humanNotifier.lastCall)
        }
    }

    @Test
    fun `escalation handler with start method self-subscribes to events`() {
        runBlocking {
            val agentId = "standalone-agent"
            val api: AgentMessageApi = apiFactory.create(agentId)

            // Create handler with EventBus and scope for standalone mode
            val standaloneHandler = EscalationEventHandler(
                coroutineScope = scope,
                humanNotifier = humanNotifier,
                eventSerialBus = eventSerialBus,
                agentId = agentId,
            )

            // Start the handler (self-subscribes to EventBus)
            standaloneHandler.start()

            // Create a thread
            val thread: MessageThread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Test",
            )

            val reason = "Standalone mode escalation"
            val ctx = mapOf("mode" to "standalone")

            api.escalateToHuman(
                threadId = thread.id,
                reason = reason,
                context = ctx,
            )

            // let async handlers run
            delay(200)

            val call = humanNotifier.lastCall
            assertNotNull(call)
            assertEquals(thread.id, call.threadId)
            assertEquals(agentId, call.agentId)
            assertEquals(reason, call.reason)
            assertEquals(ctx, call.context)
        }
    }
}
