package link.socket.ampere.agents.events.messages

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.events.EventStatus
import kotlin.time.Duration.Companion.hours
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewServiceTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var messageRepository: MessageRepository
    private lateinit var threadViewService: ThreadViewService

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        messageRepository = MessageRepository(DEFAULT_JSON, scope, database)
        threadViewService = DefaultThreadViewService(messageRepository)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `listActiveThreads returns empty list when no threads exist`() {
        runBlocking {
            val result = threadViewService.listActiveThreads()

            assertTrue(result.isSuccess)
            val threads = result.getOrNull()
            assertNotNull(threads)
            assertTrue(threads.isEmpty())
        }
    }

    @Test
    fun `listActiveThreads returns thread with correct summary information`() {
        runBlocking {
            // Create a thread with messages
            val message = Message(
                id = "msg-1",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-1"),
                content = "Hello, this is a test message",
                timestamp = Clock.System.now(),
            )
            val thread = MessageThread.create(
                id = "thread-1",
                channel = MessageChannel.Public.Engineering,
                initialMessage = message,
            )
            messageRepository.saveThread(thread)

            // Get active threads
            val result = threadViewService.listActiveThreads()

            assertTrue(result.isSuccess)
            val threads = result.getOrNull()
            assertNotNull(threads)
            assertEquals(1, threads.size)

            val summary = threads.first()
            assertEquals("thread-1", summary.threadId)
            assertEquals(1, summary.messageCount)
            assertEquals(listOf("agent-1"), summary.participantIds)
            assertFalse(summary.hasUnreadEscalations)
            assertTrue(summary.title.contains("#engineering"))
        }
    }

    @Test
    fun `listActiveThreads excludes threads with no messages`() {
        runBlocking {
            // Create a thread with a message
            val message1 = Message(
                id = "msg-1",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-1"),
                content = "Message 1",
                timestamp = Clock.System.now(),
            )
            val threadWithMessages = MessageThread.create(
                id = "thread-1",
                channel = MessageChannel.Public.Engineering,
                initialMessage = message1,
            )
            messageRepository.saveThread(threadWithMessages)

            // Create an empty thread (by manually creating one without messages)
            val emptyThread = MessageThread(
                id = "thread-2",
                channel = MessageChannel.Public.Design,
                createdBy = MessageSender.Agent("agent-2"),
                participants = setOf(MessageSender.Agent("agent-2")),
                messages = emptyList(),
                status = EventStatus.OPEN,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            )
            messageRepository.saveThread(emptyThread)

            // Get active threads
            val result = threadViewService.listActiveThreads()

            assertTrue(result.isSuccess)
            val threads = result.getOrNull()
            assertNotNull(threads)
            assertEquals(1, threads.size)
            assertEquals("thread-1", threads.first().threadId)
        }
    }

    @Test
    fun `listActiveThreads shows correct participant count with multiple participants`() {
        runBlocking {
            val message1 = Message(
                id = "msg-1",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-1"),
                content = "Message from agent 1",
                timestamp = Clock.System.now(),
            )
            val thread = MessageThread.create(
                id = "thread-1",
                channel = MessageChannel.Public.Engineering,
                initialMessage = message1,
            )

            // Add messages from different participants
            val message2 = Message(
                id = "msg-2",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-2"),
                content = "Message from agent 2",
                timestamp = Clock.System.now(),
            )
            val message3 = Message(
                id = "msg-3",
                threadId = "thread-1",
                sender = MessageSender.Human,
                content = "Message from human",
                timestamp = Clock.System.now(),
            )

            messageRepository.saveThread(thread)
            messageRepository.addMessageToThread("thread-1", message2)
            messageRepository.addMessageToThread("thread-1", message3)

            // Get active threads
            val result = threadViewService.listActiveThreads()

            assertTrue(result.isSuccess)
            val threads = result.getOrNull()
            assertNotNull(threads)
            assertEquals(1, threads.size)

            val summary = threads.first()
            assertEquals(3, summary.messageCount)
            assertEquals(3, summary.participantIds.size)
            assertTrue(summary.participantIds.contains("agent-1"))
            assertTrue(summary.participantIds.contains("agent-2"))
            assertTrue(summary.participantIds.contains("human"))
        }
    }

    @Test
    fun `listActiveThreads shows hasUnreadEscalations when status is WAITING_FOR_HUMAN`() {
        runBlocking {
            val message = Message(
                id = "msg-1",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-1"),
                content = "Need human help",
                timestamp = Clock.System.now(),
            )
            val thread = MessageThread.create(
                id = "thread-1",
                channel = MessageChannel.Public.Engineering,
                initialMessage = message,
            )
            messageRepository.saveThread(thread)

            // Update status to WAITING_FOR_HUMAN
            messageRepository.updateStatus("thread-1", EventStatus.WAITING_FOR_HUMAN)

            // Get active threads
            val result = threadViewService.listActiveThreads()

            assertTrue(result.isSuccess)
            val threads = result.getOrNull()
            assertNotNull(threads)
            assertEquals(1, threads.size)
            assertTrue(threads.first().hasUnreadEscalations)
        }
    }

    @Test
    fun `listActiveThreads excludes resolved threads`() {
        runBlocking {
            // Create two threads
            val message1 = Message(
                id = "msg-1",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-1"),
                content = "Active thread",
                timestamp = Clock.System.now(),
            )
            val activeThread = MessageThread.create(
                id = "thread-1",
                channel = MessageChannel.Public.Engineering,
                initialMessage = message1,
            )
            messageRepository.saveThread(activeThread)

            val message2 = Message(
                id = "msg-2",
                threadId = "thread-2",
                sender = MessageSender.Agent("agent-2"),
                content = "Resolved thread",
                timestamp = Clock.System.now(),
            )
            val resolvedThread = MessageThread.create(
                id = "thread-2",
                channel = MessageChannel.Public.Design,
                initialMessage = message2,
            )
            messageRepository.saveThread(resolvedThread)
            messageRepository.updateStatus("thread-2", EventStatus.RESOLVED)

            // Get active threads
            val result = threadViewService.listActiveThreads()

            assertTrue(result.isSuccess)
            val threads = result.getOrNull()
            assertNotNull(threads)
            assertEquals(1, threads.size)
            assertEquals("thread-1", threads.first().threadId)
        }
    }

    @Test
    fun `listActiveThreads sorts threads by last activity descending`() {
        runBlocking {
            // Create threads with different timestamps
            val now = Clock.System.now()

            val oldMessage = Message(
                id = "msg-1",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-1"),
                content = "Old thread",
                timestamp = now.minus(2.hours),
            )
            val oldThread = MessageThread.create(
                id = "thread-1",
                channel = MessageChannel.Public.Engineering,
                initialMessage = oldMessage,
            )

            val recentMessage = Message(
                id = "msg-2",
                threadId = "thread-2",
                sender = MessageSender.Agent("agent-2"),
                content = "Recent thread",
                timestamp = now,
            )
            val recentThread = MessageThread.create(
                id = "thread-2",
                channel = MessageChannel.Public.Design,
                initialMessage = recentMessage,
            )

            messageRepository.saveThread(oldThread)
            messageRepository.saveThread(recentThread)

            // Get active threads
            val result = threadViewService.listActiveThreads()

            assertTrue(result.isSuccess)
            val threads = result.getOrNull()
            assertNotNull(threads)
            assertEquals(2, threads.size)
            // Most recent should be first
            assertEquals("thread-2", threads[0].threadId)
            assertEquals("thread-1", threads[1].threadId)
        }
    }

    @Test
    fun `getThreadDetail returns complete thread information`() {
        runBlocking {
            val message1 = Message(
                id = "msg-1",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-1"),
                content = "First message",
                timestamp = Clock.System.now(),
            )
            val thread = MessageThread.create(
                id = "thread-1",
                channel = MessageChannel.Public.Engineering,
                initialMessage = message1,
            )

            val message2 = Message(
                id = "msg-2",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-2"),
                content = "Second message",
                timestamp = Clock.System.now(),
            )

            messageRepository.saveThread(thread)
            messageRepository.addMessageToThread("thread-1", message2)

            // Get thread detail
            val result = threadViewService.getThreadDetail("thread-1")

            assertTrue(result.isSuccess)
            val detail = result.getOrNull()
            assertNotNull(detail)
            assertEquals("thread-1", detail.threadId)
            assertEquals(2, detail.messages.size)
            assertEquals(2, detail.participants.size)
            assertTrue(detail.title.contains("#engineering"))

            // Verify messages are sorted by timestamp
            assertEquals("msg-1", detail.messages[0].id)
            assertEquals("msg-2", detail.messages[1].id)
        }
    }

    @Test
    fun `getThreadDetail returns failure for non-existent thread`() {
        runBlocking {
            val result = threadViewService.getThreadDetail("non-existent-thread")

            assertTrue(result.isFailure)
        }
    }

    @Test
    fun `thread title includes channel name and message preview for public channel`() {
        runBlocking {
            val message = Message(
                id = "msg-1",
                threadId = "thread-1",
                sender = MessageSender.Agent("agent-1"),
                content = "This is a long message that should be truncated in the preview text",
                timestamp = Clock.System.now(),
            )
            val thread = MessageThread.create(
                id = "thread-1",
                channel = MessageChannel.Public.Product,
                initialMessage = message,
            )
            messageRepository.saveThread(thread)

            val result = threadViewService.listActiveThreads()

            assertTrue(result.isSuccess)
            val threads = result.getOrNull()
            assertNotNull(threads)
            assertEquals(1, threads.size)

            val summary = threads.first()
            assertTrue(summary.title.startsWith("#product:"))
            assertTrue(summary.title.contains("This is a long message that should be truncated"))
        }
    }

    @Test
    fun `thread title shows DM format for direct messages`() {
        runBlocking {
            val sender = MessageSender.Agent("agent-1")
            val message = Message(
                id = "msg-1",
                threadId = "thread-1",
                sender = sender,
                content = "Direct message content",
                timestamp = Clock.System.now(),
            )
            val thread = MessageThread.create(
                id = "thread-1",
                channel = MessageChannel.Direct(sender),
                initialMessage = message,
            )
            messageRepository.saveThread(thread)

            val result = threadViewService.listActiveThreads()

            assertTrue(result.isSuccess)
            val threads = result.getOrNull()
            assertNotNull(threads)
            assertEquals(1, threads.size)

            val summary = threads.first()
            assertTrue(summary.title.startsWith("DM with agent-1"))
        }
    }
}
