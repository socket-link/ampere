package link.socket.ampere.agents.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.concept.status.EventStatus
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageId
import link.socket.ampere.agents.events.messages.MessageRepository
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.messages.MessageSenderId
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val stubJson = DEFAULT_JSON

    private val stubThreadId = "t1"
    private val stubThreadId2 = "t2"
    private val stubThreadId3 = "t3"

    private val stubMessageId = "m1"
    private val stubMessageId2 = "m2"

    private val stubSenderId = "a1"
    private val stubSenderId2 = "a2"

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: MessageRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database.Companion(driver)
        repo = MessageRepository(stubJson, testScope, database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    fun createStubMessage(
        messageId: MessageId,
        threadId: MessageThreadId,
        senderId: MessageSenderId = stubSenderId,
        timestamp: Instant = Clock.System.now(),
    ): Message = Message(
        id = messageId,
        threadId = threadId,
        sender = MessageSender.fromSenderId(senderId),
        content = "Hello",
        timestamp = timestamp,
        metadata = mapOf("k" to "v"),
    )

    @Test
    fun `save and retrieve thread`() {
        runBlocking {
            val now = Clock.System.now()

            val thread = MessageThread.create(
                id = stubThreadId,
                channel = MessageChannel.Public.Engineering,
                initialMessage = createStubMessage(stubMessageId, stubThreadId),
            )

            repo.saveThread(thread)

            val fetched = repo.findThreadById(stubThreadId).getOrNull()
            assertNotNull(fetched)
            assertEquals(1, fetched.participants.size)
            assertEquals(1, fetched.messages.size)
            assertEquals("Hello", fetched.messages.first().content)
        }
    }

    @Test
    fun `add message updates timestamp`() {
        runBlocking {
            val t0 = Clock.System.now()

            val msg0 = createStubMessage(
                messageId = stubMessageId,
                threadId = stubThreadId2,
                senderId = stubSenderId,
                timestamp = t0,
            )

            val thread = MessageThread.create(
                id = stubThreadId2,
                channel = MessageChannel.Public.Design,
                initialMessage = msg0,
            )
            repo.saveThread(thread)

            val t1 = t0 + 1000.seconds
            val msg1 = createStubMessage(
                messageId = stubMessageId2,
                threadId = stubThreadId2,
                senderId = stubSenderId2,
                timestamp = t1,
            )
            repo.addMessageToThread(stubThreadId2, msg1)

            val fetched = repo.findThreadById(stubThreadId2).getOrNull()
            assertNotNull(fetched)
            assertEquals(2, fetched.participants.size)
            assertEquals(2, fetched.messages.size)
            assert(fetched.updatedAt - t1 <= 1.seconds)
            assert(fetched.messages.last().timestamp - t1 <= 1.seconds)
        }
    }

    @Test
    fun update_status_and_cascade_delete() {
        runBlocking {
            val thread = MessageThread.create(
                id = stubThreadId3,
                channel = MessageChannel.Direct(MessageSender.Agent(stubSenderId)),
                initialMessage = createStubMessage(stubMessageId, stubThreadId3),
            )
            repo.saveThread(thread)

            repo.updateStatus(stubThreadId3, EventStatus.WaitingForHuman)
            val updated = repo.findThreadById(stubThreadId3).getOrNull()
            assertNotNull(updated)
            assertEquals(EventStatus.WaitingForHuman, updated.status)

            // now delete conversation, ensure cascade removes messages/participants
            repo.delete(stubThreadId3)
            val deleted = repo.findThreadById(stubThreadId3).getOrNull()
            assertNull(deleted)
        }
    }
}
