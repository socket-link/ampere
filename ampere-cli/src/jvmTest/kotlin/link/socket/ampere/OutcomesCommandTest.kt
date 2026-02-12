package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains

/**
 * Tests for OutcomesCommand and its subcommands.
 *
 * These tests verify command-line parsing and help text.
 * Business logic is tested in OutcomeMemoryRepositoryTest.
 *
 * Note: We don't test actual command execution here because the Clikt testing framework
 * doesn't properly capture output from runBlocking suspending functions. The business logic
 * is thoroughly tested in OutcomeMemoryRepositoryTest.
 */
class OutcomesCommandTest {

    /**
     * Create a test context for use in tests.
     */
    private fun createTestContext(tempDir: File): AmpereContext {
        val dbPath = File(tempDir, "test.db").absolutePath
        return AmpereContext(databasePath = dbPath)
    }

    /**
     * Mock OutcomeMemoryRepository that returns test data.
     */
    private class MockOutcomeMemoryRepository : OutcomeMemoryRepository {
        override suspend fun recordOutcome(
            ticketId: TicketId,
            executorId: ExecutorId,
            approach: String,
            outcome: ExecutionOutcome,
            timestamp: Instant,
        ): Result<OutcomeMemory> {
            return Result.failure(Exception("Not implemented"))
        }

        override suspend fun findSimilarOutcomes(
            description: String,
            limit: Int,
        ): Result<List<OutcomeMemory>> = Result.success(emptyList())

        override suspend fun getOutcomesByTicket(
            ticketId: TicketId,
        ): Result<List<OutcomeMemory>> = Result.success(emptyList())

        override suspend fun getOutcomesByExecutor(
            executorId: ExecutorId,
            limit: Int,
        ): Result<List<OutcomeMemory>> = Result.success(emptyList())
    }

    @Test
    fun `outcomes command help text shows usage`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val command = OutcomesCommand(mockRepository)
        val result = command.test("--help")

        assertContains(result.output, "View execution outcomes")
        assertContains(result.output, "accumulated experience")
    }

    @Test
    fun `outcomes ticket command help text shows usage`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val command = OutcomesCommand(mockRepository)
        val result = command.test("ticket --help")

        assertContains(result.output, "Show execution history")
        assertContains(result.output, "ticket-id")
    }

    @Test
    fun `outcomes search command help text shows usage`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val command = OutcomesCommand(mockRepository)
        val result = command.test("search --help")

        assertContains(result.output, "Find outcomes similar")
        assertContains(result.output, "query")
        assertContains(result.output, "--limit")
    }

    @Test
    fun `outcomes executor command help text shows usage`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val command = OutcomesCommand(mockRepository)
        val result = command.test("executor --help")

        assertContains(result.output, "Show outcomes for a specific executor")
        assertContains(result.output, "executor-id")
        assertContains(result.output, "--limit")
    }

    @Test
    fun `outcomes stats command help text shows usage`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val command = OutcomesCommand(mockRepository)
        val result = command.test("stats --help")

        assertContains(result.output, "Show aggregate outcome statistics")
    }

    @Test
    fun `outcomes command can be created with real context`(@TempDir tempDir: File) {
        val context = createTestContext(tempDir)
        try {
            context.start()
            val command = OutcomesCommand(context.outcomeMemoryRepository)
            val result = command.test("--help")

            assertContains(result.output, "View execution outcomes")
        } finally {
            context.close()
        }
    }
}
