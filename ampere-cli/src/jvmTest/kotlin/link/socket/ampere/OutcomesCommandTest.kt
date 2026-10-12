package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.api.model.OutcomeStats
import link.socket.ampere.api.service.OutcomeService
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
        return AmpereContext(databasePath = dbPath, workspace = testWorkspace(tempDir))
    }

    /**
     * Mock OutcomeService that returns test data.
     */
    private class MockOutcomeService : OutcomeService {
        override suspend fun forTicket(ticketId: TicketId): Result<List<OutcomeMemory>> =
            Result.success(emptyList())

        override suspend fun search(query: String, limit: Int): Result<List<OutcomeMemory>> =
            Result.success(emptyList())

        override suspend fun stats(): Result<OutcomeStats> =
            Result.success(OutcomeStats(totalOutcomes = 0, successCount = 0, failureCount = 0, successRate = 0.0, averageDurationMs = 0))

        override suspend fun byExecutor(executorId: ExecutorId, limit: Int): Result<List<OutcomeMemory>> =
            Result.success(emptyList())
    }

    @Test
    fun `outcomes command help text shows usage`() {
        val mockService = MockOutcomeService()
        val command = OutcomesCommand(mockService)
        val result = command.test("--help")

        assertContains(result.output, "View execution outcomes")
        assertContains(result.output, "accumulated experience")
    }

    @Test
    fun `outcomes ticket command help text shows usage`() {
        val mockService = MockOutcomeService()
        val command = OutcomesCommand(mockService)
        val result = command.test("ticket --help")

        assertContains(result.output, "Show execution history")
        assertContains(result.output, "ticket-id")
    }

    @Test
    fun `outcomes search command help text shows usage`() {
        val mockService = MockOutcomeService()
        val command = OutcomesCommand(mockService)
        val result = command.test("search --help")

        assertContains(result.output, "Find outcomes similar")
        assertContains(result.output, "query")
        assertContains(result.output, "--limit")
    }

    @Test
    fun `outcomes executor command help text shows usage`() {
        val mockService = MockOutcomeService()
        val command = OutcomesCommand(mockService)
        val result = command.test("executor --help")

        assertContains(result.output, "Show outcomes for a specific executor")
        assertContains(result.output, "executor-id")
        assertContains(result.output, "--limit")
    }

    @Test
    fun `outcomes executor help says it filters by the executor and not the agent`() {
        val result = OutcomesCommand(MockOutcomeService()).test("executor --help")
        val help = result.output.replace(Regex("\\s+"), " ")

        // AMPR-443 (F17): `executor` used to be read as the agent, because the agent's id was
        // what the column held.
        assertContains(help, "Filters by the executor that performed the work")
        assertContains(help, "not the agent that decided it")
        assertContains(help, "outcomes actor")
    }

    @Test
    fun `outcomes actor command help text shows usage`() {
        val result = OutcomesCommand(MockOutcomeService()).test("actor --help")
        val help = result.output.replace(Regex("\\s+"), " ")

        assertContains(help, "Show outcomes for a specific agent")
        assertContains(help, "actor-id")
        assertContains(help, "--limit")
    }

    @Test
    fun `outcomes lists the actor subcommand`() {
        val result = OutcomesCommand(MockOutcomeService()).test("--help")

        assertContains(result.output, "actor")
        assertContains(result.output, "executor")
    }

    @Test
    fun `outcomes stats command help text shows usage`() {
        val mockService = MockOutcomeService()
        val command = OutcomesCommand(mockService)
        val result = command.test("stats --help")

        assertContains(result.output, "Show aggregate outcome statistics")
    }

    @Test
    fun `outcomes command can be created with real context`(@TempDir tempDir: File) {
        val context = createTestContext(tempDir)
        try {
            context.start()
            val command = OutcomesCommand(context.ampereInstance.outcomes)
            val result = command.test("--help")

            assertContains(result.output, "View execution outcomes")
        } finally {
            context.close()
        }
    }
}


/** AMPR-300: every context needs an explicit agent workspace; tests pin it to their temp dir. */
private fun testWorkspace(tempDir: File): ExecutionWorkspace =
    ExecutionWorkspace(baseDirectory = tempDir.absolutePath)
