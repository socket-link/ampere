package link.socket.ampere.agents.events.messages

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.concept.status.EventStatus
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class AgentMessageApiTest {

    private val stubAgentId = "agent-A"
    private val stubAgentId2 = "agent-B"
    private val json = DEFAULT_JSON
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var eventSerialBus: EventSerialBus
    private lateinit var agentMessageApiFactory: AgentMessageApiFactory

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database.Companion(driver)

        eventRepository = EventRepository(json, scope, database)
        messageRepository = MessageRepository(json, scope, database)
        eventSerialBus = eventSerialBusFactory.create()
        agentMessageApiFactory = AgentMessageApiFactory(messageRepository, eventSerialBus)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `create thread, escalate status, then resolve`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)
            val received = mutableListOf<MessageEvent>()

            val threadCreatedSubscription = api.onThreadCreated { event, _ ->
                received += event
            }

            val escalationRequestedSubscription = api.onEscalationRequested { event, _ ->
                received += event
            }

            val threadStatusChangedSubscription = api.onThreadStatusChanged { event, _ ->
                received += event
            }

            // Subscribe to channel message posts to capture initial and subsequent messages
            val channelMessagePostedSubscription = api.onChannelMessagePosted(
                channel = MessageChannel.Public.Engineering,
            ) { event, _ ->
                received += event
            }

            // Create
            val thread = api.createThread(
                participants = setOf(stubAgentId2),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Kickoff",
            )

            val fetchedThread1 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread1)
            assertEquals(EventStatus.Open, fetchedThread1.status)
            assertEquals(2, fetchedThread1.participants.size) // sender + agent-B
            assertEquals(1, fetchedThread1.messages.size)

            // Post
            val message = api.postMessage(
                threadId = thread.id,
                content = "Update",
            )
            assertEquals("Update", message.content)

            val fetchedThread2 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread2)
            assertEquals(2, fetchedThread2.messages.size)

            // Escalate -> WAITING_FOR_HUMAN
            api.escalateToHuman(
                threadId = thread.id,
                reason = "Need approval",
            )
            val fetchedThread3 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread3)
            assertEquals(EventStatus.WaitingForHuman, fetchedThread3.status)

            // Posting now should fail, since the thread is waiting for human
            var threw = false
            try {
                api.postMessage(
                    threadId = thread.id,
                    content = "Should fail",
                )
            } catch (e: IllegalArgumentException) {
                threw = true
            } catch (e: IllegalStateException) {
                threw = true
            }
            assertTrue(threw)

            // Resolve
            api.resolveThread(thread.id)
            val fetched4 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetched4)
            assertEquals(EventStatus.Resolved, fetched4.status)

            // allow async event handlers to run
            delay(200)

            // Events were published (at least 1 create, 2 posts, 1 escalation, 2 status changes)
            assertTrue(received.any { it is MessageEvent.ThreadCreated })
            assertTrue(received.count { it is MessageEvent.MessagePosted } >= 2)
            assertTrue(received.any { it is MessageEvent.EscalationRequested })
            assertTrue(received.count { it is MessageEvent.ThreadStatusChanged } >= 2)

            // ** TODO: Test subscriptions can be unsubscribed from. */
        }
    }

    @Test
    fun `reopen thread allows posting after escalation`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)
            val received = mutableListOf<MessageEvent>()

            api.onThreadStatusChanged { event, _ ->
                received += event
            }

            // Create thread
            val thread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Initial message",
            )

            // Verify initial status is OPEN
            val fetchedThread1 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread1)
            assertEquals(EventStatus.Open, fetchedThread1.status)

            // Escalate to WAITING_FOR_HUMAN
            api.escalateToHuman(
                threadId = thread.id,
                reason = "Need human input",
            )

            val fetchedThread2 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread2)
            assertEquals(EventStatus.WaitingForHuman, fetchedThread2.status)

            // Verify posting is blocked
            var blocked = false
            try {
                api.postMessage(thread.id, "Should fail")
            } catch (e: IllegalArgumentException) {
                blocked = true
            }
            assertTrue(blocked, "Posting should be blocked when waiting for human")

            // Reopen the thread
            api.reopenThread(thread.id)

            val fetchedThread3 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread3)
            assertEquals(EventStatus.Open, fetchedThread3.status)

            // Now posting should succeed
            val newMessage = api.postMessage(thread.id, "Human intervention complete")
            assertEquals("Human intervention complete", newMessage.content)

            // Verify thread has the new message
            val fetchedThread4 = api.getThread(thread.id).getOrNull()
            assertNotNull(fetchedThread4)
            assertEquals(2, fetchedThread4.messages.size)

            // Allow async handlers to run
            delay(200)

            // Verify status change events
            val statusChanges = received.filterIsInstance<MessageEvent.ThreadStatusChanged>()
            assertTrue(statusChanges.size >= 2)
            assertTrue(statusChanges.any { it.newStatus == EventStatus.WaitingForHuman})
            assertTrue(statusChanges.any { it.newStatus == EventStatus.Open})
        }
    }

    @Test
    fun `reopen thread fails when not in WAITING_FOR_HUMAN state`() {
        runBlocking {
            val api = agentMessageApiFactory.create(stubAgentId)

            // Create thread (starts in OPEN state)
            val thread = api.createThread(
                participants = emptySet(),
                channel = MessageChannel.Public.Engineering,
                initialMessageContent = "Test message",
            )

            // Attempting to reopen an OPEN thread should fail
            var threw = false
            var errorMessage: String? = null
            try {
                api.reopenThread(thread.id)
            } catch (e: Exception) {
                threw = true
                errorMessage = e.message
            }
            assertTrue(threw, "Should throw when reopening a non-WAITING_FOR_HUMAN thread")
            assertTrue(
                errorMessage?.contains("waiting for human") == true,
                "Error message should mention waiting for human, was: $errorMessage",
            )
        }
    }
}
