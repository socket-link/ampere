package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import link.socket.ampere.agents.events.messages.ThreadDetail
import link.socket.ampere.agents.events.messages.ThreadSummary
import link.socket.ampere.agents.events.messages.ThreadViewService
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
     * Mock ThreadViewService that returns test data.
     */
    private class MockThreadViewService : ThreadViewService {
        var listActiveThreadsResult: Result<List<ThreadSummary>> = Result.success(emptyList())
        var getThreadDetailResult: Result<ThreadDetail>? = null

        override suspend fun listActiveThreads(): Result<List<ThreadSummary>> =
            listActiveThreadsResult

        override suspend fun getThreadDetail(threadId: String): Result<ThreadDetail> =
            getThreadDetailResult ?: Result.failure(Exception("Thread not found"))
    }

    @Test
    fun `thread command help text shows usage`() {
        val mockService = MockThreadViewService()
        val command = ThreadCommand(mockService)
        val result = command.test("--help")

        assertContains(result.output, "View conversation threads")
        assertContains(result.output, "thread")
    }

    @Test
    fun `thread list command help text shows usage`() {
        val mockService = MockThreadViewService()
        val command = ThreadCommand(mockService)
        val result = command.test("list --help")

        assertContains(result.output, "List all active threads")
    }

    @Test
    fun `thread show command help text shows usage`() {
        val mockService = MockThreadViewService()
        val command = ThreadCommand(mockService)
        val result = command.test("show --help")

        assertContains(result.output, "Display full thread conversation")
        assertContains(result.output, "thread-id")
    }

    @Test
    fun `thread command can be created with real context`(@TempDir tempDir: File) {
        val context = createTestContext(tempDir)
        try {
            val command = ThreadCommand(context.threadViewService)
            val result = command.test("--help")

            assertContains(result.output, "View conversation threads")
        } finally {
            context.close()
        }
    }
}
