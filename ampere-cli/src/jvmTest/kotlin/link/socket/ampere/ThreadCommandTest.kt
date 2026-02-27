package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import link.socket.ampere.agents.events.messages.Message
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.messages.MessageThread
import link.socket.ampere.agents.events.messages.MessageThreadId
import link.socket.ampere.agents.events.messages.ThreadDetail
import link.socket.ampere.agents.events.messages.ThreadSummary
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.api.model.ThreadFilter
import link.socket.ampere.api.service.ThreadBuilder
import link.socket.ampere.api.service.ThreadService
import kotlinx.datetime.Clock
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains

/**
 * Tests for ThreadCommand and its subcommands.
 *
 * These tests verify command-line parsing and help text.
 * Integration testing with real data is done through ThreadViewServiceTest.
 *
 * Note: We don't test actual command execution here because the Clikt testing framework
 * doesn't properly capture output from runBlocking suspending functions. The business logic
 * is thoroughly tested in ThreadViewServiceTest.
 */
class ThreadCommandTest {

    /**
     * Create a test context for use in tests.
     */
    private fun createTestContext(tempDir: File): AmpereContext {
        val dbPath = File(tempDir, "test.db").absolutePath
        return AmpereContext(databasePath = dbPath)
    }

    /**
     * Mock ThreadService that returns test data.
     */
    private class MockThreadService : ThreadService {
        private val now = Clock.System.now()

        override suspend fun create(title: String, configure: (ThreadBuilder.() -> Unit)?): Result<MessageThread> {
            return Result.success(
                MessageThread(
                    id = "mock-thread",
                    channel = MessageChannel.Public.Engineering,
                    createdBy = MessageSender.Human,
                    participants = setOf(MessageSender.Human),
                    messages = emptyList(),
                    status = EventStatus.Open,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }

        override suspend fun post(threadId: MessageThreadId, content: String, senderId: String): Result<Message> {
            return Result.success(
                Message(id = "mock-msg", threadId = threadId, sender = MessageSender.Human, content = content, timestamp = now)
            )
        }

        override suspend fun get(threadId: MessageThreadId): Result<ThreadDetail> =
            Result.success(ThreadDetail(threadId = threadId, title = "Thread", messages = emptyList(), participants = emptyList()))

        override suspend fun list(filter: ThreadFilter?): Result<List<ThreadSummary>> =
            Result.success(emptyList())

        override fun observe(threadId: MessageThreadId): Flow<Message> = emptyFlow()
    }

    @Test
    fun `thread command help text shows usage`() {
        val mockService = MockThreadService()
        val command = ThreadCommand(mockService)
        val result = command.test("--help")

        assertContains(result.output, "View conversation threads")
        assertContains(result.output, "thread")
    }

    @Test
    fun `thread list command help text shows usage`() {
        val mockService = MockThreadService()
        val command = ThreadCommand(mockService)
        val result = command.test("list --help")

        assertContains(result.output, "List all active threads")
    }

    @Test
    fun `thread show command help text shows usage`() {
        val mockService = MockThreadService()
        val command = ThreadCommand(mockService)
        val result = command.test("show --help")

        assertContains(result.output, "Display full thread conversation")
        assertContains(result.output, "thread-id")
    }

    @Test
    fun `thread command can be created with real context`(@TempDir tempDir: File) {
        val context = createTestContext(tempDir)
        try {
            val command = ThreadCommand(context.ampereInstance.threads)
            val result = command.test("--help")

            assertContains(result.output, "View conversation threads")
        } finally {
            context.close()
        }
    }
}
