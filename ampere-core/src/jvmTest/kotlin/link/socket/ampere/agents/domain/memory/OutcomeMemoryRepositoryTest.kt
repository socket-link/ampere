package link.socket.ampere.agents.domain.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.task.TaskId
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.db.Database

/**
 * Comprehensive test suite for OutcomeMemoryRepository.
 *
 * Tests cover the complete lifecycle of outcome storage and retrieval,
 * including success/failure scenarios, similarity search, and edge cases.
 */
class OutcomeMemoryRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repo: OutcomeMemoryRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        repo = OutcomeMemoryRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    // ==================== Helper Methods ====================

    private val now = Clock.System.now()
    private val ticketId1: TicketId = "ticket-1"
    private val ticketId2: TicketId = "ticket-2"
    private val executorId1: ExecutorId = "executor-1"
    private val executorId2: ExecutorId = "executor-2"
    private val taskId: TaskId = "task-1"

    private fun createSuccessfulOutcome(
        ticketId: TicketId = ticketId1,
        executorId: ExecutorId = executorId1,
        changedFiles: List<String> = listOf("file1.kt", "file2.kt"),
    ): ExecutionOutcome.CodeChanged.Success {
        return ExecutionOutcome.CodeChanged.Success(
            executorId = executorId,
            ticketId = ticketId,
            taskId = taskId,
            executionStartTimestamp = now,
            executionEndTimestamp = now.plus(5.seconds),
            changedFiles = changedFiles,
            validation = ExecutionResult(
                codeChanges = null,
                compilation = null,
                linting = null,
                tests = null,
            ),
        )
    }

    private fun createFailedOutcome(
        ticketId: TicketId = ticketId1,
        executorId: ExecutorId = executorId1,
        errorMessage: String = "Test error",
    ): ExecutionOutcome.CodeChanged.Failure {
        return ExecutionOutcome.CodeChanged.Failure(
            executorId = executorId,
            ticketId = ticketId,
            taskId = taskId,
            executionStartTimestamp = now,
            executionEndTimestamp = now.plus(3.seconds),
            error = ExecutionError(
                type = ExecutionError.Type.UNEXPECTED,
                message = errorMessage,
            ),
            partiallyChangedFiles = listOf("file1.kt"),
        )
    }

    // =============================================================================
    // RECORD OUTCOME TESTS
    // =============================================================================

    @Test
    fun `recordOutcome successfully stores and retrieves successful outcome`() = runBlocking {
        val outcome = createSuccessfulOutcome()
        val approach = "Add input validation to UserRepository"

        val result = repo.recordOutcome(
            ticketId = ticketId1,
            executorId = executorId1,
            approach = approach,
            outcome = outcome,
            timestamp = outcome.executionEndTimestamp,
        )

        assertTrue(result.isSuccess)
        val stored = result.getOrNull()
        assertNotNull(stored)
        assertEquals(ticketId1, stored.ticketId)
        assertEquals(executorId1, stored.executorId)
        assertEquals(approach, stored.approach)
        assertTrue(stored.success)
        assertEquals(2, stored.filesChanged)
        assertNull(stored.errorMessage)
        assertTrue(stored.executionDurationMs > 0)
    }

    @Test
    fun `recordOutcome successfully stores and retrieves failed outcome`() = runBlocking {
        val errorMessage = "Compilation failed: missing import"
        val outcome = createFailedOutcome(errorMessage = errorMessage)
        val approach = "Add new feature to OrderService"

        val result = repo.recordOutcome(
            ticketId = ticketId1,
            executorId = executorId1,
            approach = approach,
            outcome = outcome,
            timestamp = outcome.executionEndTimestamp,
        )

        assertTrue(result.isSuccess)
        val stored = result.getOrNull()
        assertNotNull(stored)
        assertEquals(ticketId1, stored.ticketId)
        assertEquals(executorId1, stored.executorId)
        assertEquals(approach, stored.approach)
        assertFalse(stored.success)
        assertEquals(1, stored.filesChanged) // partiallyChangedFiles
        assertNotNull(stored.errorMessage)
        assertTrue(stored.errorMessage!!.contains(errorMessage))
    }

    @Test
    fun `recordOutcome stores execution duration correctly`() = runBlocking {
        val startTime = now
        val endTime = now.plus(10.seconds)
        val outcome = ExecutionOutcome.CodeChanged.Success(
            executorId = executorId1,
            ticketId = ticketId1,
            taskId = taskId,
            executionStartTimestamp = startTime,
            executionEndTimestamp = endTime,
            changedFiles = listOf("file1.kt"),
            validation = ExecutionResult(null, null, null, null),
        )

        val result = repo.recordOutcome(
            ticketId = ticketId1,
            executorId = executorId1,
            approach = "Test approach",
            outcome = outcome,
            timestamp = endTime,
        )

        assertTrue(result.isSuccess)
        val stored = result.getOrNull()
        assertNotNull(stored)
        assertEquals(10_000L, stored.executionDurationMs)
    }

    // =============================================================================
    // GET OUTCOMES BY TICKET TESTS
    // =============================================================================

    @Test
    fun `getOutcomesByTicket returns all outcomes for a ticket in chronological order`() = runBlocking {
        // Create three outcomes for the same ticket at different times
        val outcome1 = createSuccessfulOutcome()
        val outcome2 = createFailedOutcome()
        val outcome3 = createSuccessfulOutcome()

        repo.recordOutcome(ticketId1, executorId1, "First attempt", outcome1, now)
        repo.recordOutcome(ticketId1, executorId1, "Second attempt", outcome2, now.plus(1.seconds))
        repo.recordOutcome(ticketId1, executorId1, "Third attempt", outcome3, now.plus(2.seconds))

        val result = repo.getOutcomesByTicket(ticketId1)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertEquals(3, outcomes.size)

        // Verify they're in reverse chronological order (most recent first)
        assertTrue(outcomes[0].timestamp > outcomes[1].timestamp)
        assertTrue(outcomes[1].timestamp > outcomes[2].timestamp)
        assertEquals("Third attempt", outcomes[0].approach)
        assertEquals("Second attempt", outcomes[1].approach)
        assertEquals("First attempt", outcomes[2].approach)
    }

    @Test
    fun `getOutcomesByTicket returns empty list for ticket with no outcomes`() = runBlocking {
        val result = repo.getOutcomesByTicket("nonexistent-ticket")

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `getOutcomesByTicket only returns outcomes for specified ticket`() = runBlocking {
        val outcome1 = createSuccessfulOutcome(ticketId = ticketId1)
        val outcome2 = createSuccessfulOutcome(ticketId = ticketId2)

        repo.recordOutcome(ticketId1, executorId1, "Ticket 1 approach", outcome1, now)
        repo.recordOutcome(ticketId2, executorId1, "Ticket 2 approach", outcome2, now)

        val result = repo.getOutcomesByTicket(ticketId1)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertEquals(1, outcomes.size)
        assertEquals(ticketId1, outcomes[0].ticketId)
        assertEquals("Ticket 1 approach", outcomes[0].approach)
    }

    // =============================================================================
    // GET OUTCOMES BY EXECUTOR TESTS
    // =============================================================================

    @Test
    fun `getOutcomesByExecutor returns outcomes for specified executor`() = runBlocking {
        val outcome1 = createSuccessfulOutcome(executorId = executorId1)
        val outcome2 = createSuccessfulOutcome(executorId = executorId2)

        repo.recordOutcome(ticketId1, executorId1, "Executor 1 work", outcome1, now)
        repo.recordOutcome(ticketId1, executorId2, "Executor 2 work", outcome2, now)

        val result = repo.getOutcomesByExecutor(executorId1, limit = 20)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertEquals(1, outcomes.size)
        assertEquals(executorId1, outcomes[0].executorId)
    }

    @Test
    fun `getOutcomesByExecutor respects limit parameter`() = runBlocking {
        // Create 5 outcomes for the same executor
        repeat(5) { i ->
            val outcome = createSuccessfulOutcome(executorId = executorId1)
            repo.recordOutcome(ticketId1, executorId1, "Attempt $i", outcome, now.plus((i * 1000).milliseconds))
        }

        val result = repo.getOutcomesByExecutor(executorId1, limit = 3)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertEquals(3, outcomes.size)
    }

    @Test
    fun `getOutcomesByExecutor returns empty list for executor with no outcomes`() = runBlocking {
        val result = repo.getOutcomesByExecutor("nonexistent-executor", limit = 20)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertTrue(outcomes.isEmpty())
    }

    // =============================================================================
    // FIND SIMILAR OUTCOMES TESTS
    // =============================================================================

    @Test
    fun `findSimilarOutcomes finds relevant matches using keywords`() = runBlocking {
        // Create outcomes with different approaches
        val outcome1 = createSuccessfulOutcome()
        val outcome2 = createSuccessfulOutcome()
        val outcome3 = createSuccessfulOutcome()

        repo.recordOutcome(ticketId1, executorId1, "Add input validation to UserRepository", outcome1, now)
        repo.recordOutcome(ticketId1, executorId1, "Add validation to OrderRepository", outcome2, now)
        repo.recordOutcome(ticketId1, executorId1, "Fix database connection error", outcome3, now)

        // Search for "validation" should find first two
        val result = repo.findSimilarOutcomes("validation", limit = 5)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertTrue(outcomes.size >= 2)

        // Verify that the results contain validation-related outcomes
        val approaches = outcomes.map { it.approach }
        assertTrue(approaches.any { it.contains("validation", ignoreCase = true) })
    }

    @Test
    fun `findSimilarOutcomes handles multiple keywords`() = runBlocking {
        val outcome1 = createSuccessfulOutcome()
        val outcome2 = createSuccessfulOutcome()
        val outcome3 = createSuccessfulOutcome()

        repo.recordOutcome(ticketId1, executorId1, "Add authentication to UserService", outcome1, now)
        repo.recordOutcome(ticketId1, executorId1, "Implement user registration flow", outcome2, now)
        repo.recordOutcome(ticketId1, executorId1, "Fix payment processing bug", outcome3, now)

        // Search for "user authentication" should find related outcomes
        val result = repo.findSimilarOutcomes("user authentication", limit = 5)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertTrue(outcomes.isNotEmpty())
    }

    @Test
    fun `findSimilarOutcomes respects limit parameter`() = runBlocking {
        // Create 10 outcomes with similar approaches
        repeat(10) { i ->
            val outcome = createSuccessfulOutcome()
            repo.recordOutcome(
                ticketId1,
                executorId1,
                "Add validation logic $i",
                outcome,
                now.plus((i * 1000).milliseconds),
            )
        }

        val result = repo.findSimilarOutcomes("validation", limit = 3)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertTrue(outcomes.size <= 3)
    }

    @Test
    fun `findSimilarOutcomes returns empty list for no matches`() = runBlocking {
        val outcome = createSuccessfulOutcome()
        repo.recordOutcome(ticketId1, executorId1, "Add authentication", outcome, now)

        val result = repo.findSimilarOutcomes("nonexistent keyword xyz", limit = 5)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `findSimilarOutcomes handles empty search string`() = runBlocking {
        val outcome = createSuccessfulOutcome()
        repo.recordOutcome(ticketId1, executorId1, "Some approach", outcome, now)

        val result = repo.findSimilarOutcomes("", limit = 5)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        // Empty search should return empty results
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `findSimilarOutcomes is case-insensitive`() = runBlocking {
        val outcome = createSuccessfulOutcome()
        repo.recordOutcome(ticketId1, executorId1, "Add VALIDATION to UserRepository", outcome, now)

        val result = repo.findSimilarOutcomes("validation", limit = 5)

        assertTrue(result.isSuccess)
        val outcomes = result.getOrNull()
        assertNotNull(outcomes)
        assertTrue(outcomes.isNotEmpty())
    }

    // =============================================================================
    // EDGE CASES AND INTEGRATION TESTS
    // =============================================================================

    @Test
    fun `complete workflow - record, query, and analyze outcomes`() = runBlocking {
        // Simulate a ticket that was attempted multiple times
        val attempts = listOf(
            Triple("First try: add validation", createFailedOutcome(errorMessage = "Missing import"), now),
            Triple(
                "Second try: fix imports and add validation",
                createFailedOutcome(errorMessage = "Test failed"),
                now.plus(1.seconds),
            ),
            Triple("Third try: fix tests and add validation", createSuccessfulOutcome(), now.plus(2.seconds)),
        )

        // Record all attempts
        attempts.forEach { (approach, outcome, timestamp) ->
            repo.recordOutcome(ticketId1, executorId1, approach, outcome, timestamp)
        }

        // Query by ticket to see progression
        val byTicket = repo.getOutcomesByTicket(ticketId1).getOrNull()
        assertNotNull(byTicket)
        assertEquals(3, byTicket.size)

        // Verify progression from failure to success
        assertEquals(true, byTicket[0].success) // Most recent (third try)
        assertEquals(false, byTicket[1].success) // Second try
        assertEquals(false, byTicket[2].success) // First try

        // Query by similarity
        val similar = repo.findSimilarOutcomes("validation", limit = 5).getOrNull()
        assertNotNull(similar)
        assertTrue(similar.size >= 3)
    }

    @Test
    fun `outcomes from different executors are stored separately`() = runBlocking {
        val outcome1 = createSuccessfulOutcome(executorId = executorId1)
        val outcome2 = createSuccessfulOutcome(executorId = executorId2)

        repo.recordOutcome(ticketId1, executorId1, "Executor 1 approach", outcome1, now)
        repo.recordOutcome(ticketId1, executorId2, "Executor 2 approach", outcome2, now)

        val executor1Results = repo.getOutcomesByExecutor(executorId1, limit = 20).getOrNull()
        val executor2Results = repo.getOutcomesByExecutor(executorId2, limit = 20).getOrNull()

        assertNotNull(executor1Results)
        assertNotNull(executor2Results)
        assertEquals(1, executor1Results.size)
        assertEquals(1, executor2Results.size)
        assertEquals(executorId1, executor1Results[0].executorId)
        assertEquals(executorId2, executor2Results[0].executorId)
    }

    @Test
    fun `outcomes with different file counts are stored correctly`() = runBlocking {
        val outcomeNoFiles = createSuccessfulOutcome(changedFiles = emptyList())
        val outcomeOneFile = createSuccessfulOutcome(changedFiles = listOf("file1.kt"))
        val outcomeManyFiles = createSuccessfulOutcome(
            changedFiles = listOf("file1.kt", "file2.kt", "file3.kt", "file4.kt", "file5.kt"),
        )

        repo.recordOutcome(ticketId1, executorId1, "No files", outcomeNoFiles, now)
        repo.recordOutcome(ticketId1, executorId1, "One file", outcomeOneFile, now)
        repo.recordOutcome(ticketId1, executorId1, "Many files", outcomeManyFiles, now)

        val outcomes = repo.getOutcomesByTicket(ticketId1).getOrNull()
        assertNotNull(outcomes)
        assertEquals(3, outcomes.size)

        val fileCounts = outcomes.map { it.filesChanged }.toSet()
        assertTrue(fileCounts.contains(0))
        assertTrue(fileCounts.contains(1))
        assertTrue(fileCounts.contains(5))
    }
}
