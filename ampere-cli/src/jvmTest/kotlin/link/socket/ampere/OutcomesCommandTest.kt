package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.core.memory.OutcomeMemory
import link.socket.ampere.agents.core.memory.OutcomeMemoryRepository
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains

/**
 * Tests for OutcomesCommand and its subcommands.
 *
 * These tests verify command-line parsing, help text, and basic output formatting.
 * Business logic is tested in OutcomeMemoryRepositoryTest.
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
        var recordOutcomeResult: Result<OutcomeMemory>? = null
        var findSimilarOutcomesResult: Result<List<OutcomeMemory>> = Result.success(emptyList())
        var getOutcomesByTicketResult: Result<List<OutcomeMemory>> = Result.success(emptyList())
        var getOutcomesByExecutorResult: Result<List<OutcomeMemory>> = Result.success(emptyList())

        override suspend fun recordOutcome(
            ticketId: TicketId,
            executorId: ExecutorId,
            approach: String,
            outcome: ExecutionOutcome,
            timestamp: Instant,
        ): Result<OutcomeMemory> {
            return recordOutcomeResult ?: Result.failure(Exception("Not mocked"))
        }

        override suspend fun findSimilarOutcomes(
            description: String,
            limit: Int,
        ): Result<List<OutcomeMemory>> = findSimilarOutcomesResult

        override suspend fun getOutcomesByTicket(
            ticketId: TicketId,
        ): Result<List<OutcomeMemory>> = getOutcomesByTicketResult

        override suspend fun getOutcomesByExecutor(
            executorId: ExecutorId,
            limit: Int,
        ): Result<List<OutcomeMemory>> = getOutcomesByExecutorResult
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
    fun `outcomes ticket command with no results shows message`() {
        val mockRepository = MockOutcomeMemoryRepository()
        mockRepository.getOutcomesByTicketResult = Result.success(emptyList())

        val command = OutcomesCommand(mockRepository)
        val result = command.test("ticket AMPERE-123")

        assertContains(result.output, "No execution attempts found")
    }

    @Test
    fun `outcomes ticket command with one success shows table`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val testOutcome = OutcomeMemory(
            id = "test-id",
            ticketId = "AMPERE-123",
            executorId = "test-executor",
            approach = "Test approach",
            success = true,
            executionDurationMs = 5000,
            filesChanged = 3,
            errorMessage = null,
            timestamp = Clock.System.now(),
        )
        mockRepository.getOutcomesByTicketResult = Result.success(listOf(testOutcome))

        val command = OutcomesCommand(mockRepository)
        val result = command.test("ticket AMPERE-123")

        assertContains(result.output, "EXECUTION HISTORY")
        assertContains(result.output, "AMPERE-123")
        assertContains(result.output, "test-executor")
        assertContains(result.output, "Success")
    }

    @Test
    fun `outcomes ticket command with one failure shows error`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val testOutcome = OutcomeMemory(
            id = "test-id",
            ticketId = "AMPERE-123",
            executorId = "test-executor",
            approach = "Test approach",
            success = false,
            executionDurationMs = 5000,
            filesChanged = 1,
            errorMessage = "Test error message",
            timestamp = Clock.System.now(),
        )
        mockRepository.getOutcomesByTicketResult = Result.success(listOf(testOutcome))

        val command = OutcomesCommand(mockRepository)
        val result = command.test("ticket AMPERE-123")

        assertContains(result.output, "EXECUTION HISTORY")
        assertContains(result.output, "Failed")
        assertContains(result.output, "Test error")
    }

    @Test
    fun `outcomes ticket command with multiple outcomes shows all`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val outcomes = listOf(
            OutcomeMemory(
                id = "test-id-1",
                ticketId = "AMPERE-123",
                executorId = "executor-1",
                approach = "First approach",
                success = false,
                executionDurationMs = 5000,
                filesChanged = 1,
                errorMessage = "First error",
                timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "test-id-2",
                ticketId = "AMPERE-123",
                executorId = "executor-2",
                approach = "Second approach",
                success = true,
                executionDurationMs = 3000,
                filesChanged = 2,
                errorMessage = null,
                timestamp = Clock.System.now(),
            ),
        )
        mockRepository.getOutcomesByTicketResult = Result.success(outcomes)

        val command = OutcomesCommand(mockRepository)
        val result = command.test("ticket AMPERE-123")

        assertContains(result.output, "2 attempts")
        assertContains(result.output, "executor-1")
        assertContains(result.output, "executor-2")
        assertContains(result.output, "Success")
        assertContains(result.output, "Failed")
    }

    @Test
    fun `outcomes search command with no results shows message`() {
        val mockRepository = MockOutcomeMemoryRepository()
        mockRepository.findSimilarOutcomesResult = Result.success(emptyList())

        val command = OutcomesCommand(mockRepository)
        val result = command.test("search validation")

        assertContains(result.output, "No similar outcomes found")
    }

    @Test
    fun `outcomes search command with results shows matches`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val testOutcome = OutcomeMemory(
            id = "test-id",
            ticketId = "AMPERE-456",
            executorId = "test-executor",
            approach = "Add input validation to UserRepository",
            success = true,
            executionDurationMs = 5000,
            filesChanged = 2,
            errorMessage = null,
            timestamp = Clock.System.now(),
        )
        mockRepository.findSimilarOutcomesResult = Result.success(listOf(testOutcome))

        val command = OutcomesCommand(mockRepository)
        val result = command.test("search validation")

        assertContains(result.output, "SIMILAR OUTCOMES")
        assertContains(result.output, "1 matches")
        assertContains(result.output, "AMPERE-456")
        assertContains(result.output, "Add input validation")
    }

    @Test
    fun `outcomes executor command with no results shows message`() {
        val mockRepository = MockOutcomeMemoryRepository()
        mockRepository.getOutcomesByExecutorResult = Result.success(emptyList())

        val command = OutcomesCommand(mockRepository)
        val result = command.test("executor test-executor")

        assertContains(result.output, "No outcomes found")
    }

    @Test
    fun `outcomes executor command shows success rate calculation`() {
        val mockRepository = MockOutcomeMemoryRepository()
        val outcomes = listOf(
            OutcomeMemory(
                id = "1", ticketId = "T1", executorId = "test-executor",
                approach = "A", success = true, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = null, timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "2", ticketId = "T2", executorId = "test-executor",
                approach = "B", success = true, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = null, timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "3", ticketId = "T3", executorId = "test-executor",
                approach = "C", success = true, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = null, timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "4", ticketId = "T4", executorId = "test-executor",
                approach = "D", success = true, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = null, timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "5", ticketId = "T5", executorId = "test-executor",
                approach = "E", success = true, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = null, timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "6", ticketId = "T6", executorId = "test-executor",
                approach = "F", success = true, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = null, timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "7", ticketId = "T7", executorId = "test-executor",
                approach = "G", success = true, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = null, timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "8", ticketId = "T8", executorId = "test-executor",
                approach = "H", success = false, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = "Error 1", timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "9", ticketId = "T9", executorId = "test-executor",
                approach = "I", success = false, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = "Error 2", timestamp = Clock.System.now(),
            ),
            OutcomeMemory(
                id = "10", ticketId = "T10", executorId = "test-executor",
                approach = "J", success = false, executionDurationMs = 5000,
                filesChanged = 1, errorMessage = "Error 3", timestamp = Clock.System.now(),
            ),
        )
        mockRepository.getOutcomesByExecutorResult = Result.success(outcomes)

        val command = OutcomesCommand(mockRepository)
        val result = command.test("executor test-executor")

        assertContains(result.output, "EXECUTOR PERFORMANCE")
        assertContains(result.output, "70%") // 7 successes out of 10
        assertContains(result.output, "(7/10)")
    }

    @Test
    fun `outcomes ticket command handles repository error gracefully`() {
        val mockRepository = MockOutcomeMemoryRepository()
        mockRepository.getOutcomesByTicketResult = Result.failure(Exception("Database error"))

        val command = OutcomesCommand(mockRepository)
        val result = command.test("ticket AMPERE-123")

        assertContains(result.output, "Error")
        assertContains(result.output, "Database error")
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
