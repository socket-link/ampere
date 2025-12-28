package link.socket.ampere.agents.domain.concept.outcome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.db.Database
import link.socket.ampere.db.memory.OutcomeMemoryStore
import link.socket.ampere.db.memory.OutcomeMemoryStoreQueries

/**
 * SQLDelight-backed implementation of OutcomeMemoryRepository.
 *
 * This stores execution outcomes in a searchable database with full-text
 * search capabilities for finding similar past attempts.
 */
class OutcomeMemoryRepositoryImpl(
    private val database: Database,
) : OutcomeMemoryRepository {

    private val queries: OutcomeMemoryStoreQueries
        get() = database.outcomeMemoryStoreQueries

    override suspend fun recordOutcome(
        ticketId: TicketId,
        executorId: ExecutorId,
        approach: String,
        outcome: ExecutionOutcome,
        timestamp: Instant,
    ): Result<OutcomeMemory> = withContext(Dispatchers.IO) {
        runCatching {
            val id = generateUUID(ticketId, executorId)
            val success = outcome is ExecutionOutcome.Success
            val executionDurationMs = timestamp.toEpochMilliseconds() -
                outcome.executionStartTimestamp.toEpochMilliseconds()

            // Extract file count and error message from outcome
            val (filesChanged, errorMessage) = when (outcome) {
                is ExecutionOutcome.CodeChanged.Success -> {
                    outcome.changedFiles.size to null
                }
                is ExecutionOutcome.CodeChanged.Failure -> {
                    (outcome.partiallyChangedFiles?.size ?: 0) to outcome.error.message
                }
                is ExecutionOutcome.CodeReading.Success -> {
                    outcome.readFiles.size to null
                }
                is ExecutionOutcome.CodeReading.Failure -> {
                    (outcome.partiallyReadFiles?.size ?: 0) to outcome.error.message
                }
                is ExecutionOutcome.NoChanges.Success -> {
                    0 to null
                }
                is ExecutionOutcome.NoChanges.Failure -> {
                    0 to outcome.message
                }
                is ExecutionOutcome.IssueManagement.Success -> {
                    outcome.response.created.size to null
                }
                is ExecutionOutcome.IssueManagement.Failure -> {
                    (outcome.partialResponse?.created?.size ?: 0) to outcome.error.message
                }
                is ExecutionOutcome.Blank -> {
                    0 to null
                }
            }

            queries.insertOutcome(
                id = id,
                ticket_id = ticketId,
                executor_id = executorId,
                approach = approach,
                success = if (success) 1L else 0L,
                execution_duration_ms = executionDurationMs,
                files_changed = filesChanged.toLong(),
                error_message = errorMessage,
                timestamp = timestamp.toEpochMilliseconds(),
            )

            OutcomeMemory(
                id = id,
                ticketId = ticketId,
                executorId = executorId,
                approach = approach,
                success = success,
                executionDurationMs = executionDurationMs,
                filesChanged = filesChanged,
                errorMessage = errorMessage,
                timestamp = timestamp,
            )
        }
    }

    override suspend fun findSimilarOutcomes(
        description: String,
        limit: Int,
    ): Result<List<OutcomeMemory>> = withContext(Dispatchers.IO) {
        runCatching {
            // Try FTS search first, fall back to LIKE if FTS fails
            try {
                // Convert description to FTS5 query format
                // Split into keywords and join with OR for broader matching
                val keywords = description
                    .split(Regex("\\s+"))
                    .filter { it.length > 2 } // Skip very short words
                    .joinToString(" OR ")

                if (keywords.isNotEmpty()) {
                    queries.findSimilarOutcomes(keywords, limit.toLong())
                        .executeAsList()
                        .map { row -> mapRowToOutcomeMemory(row) }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                // Fall back to LIKE-based search if FTS fails
                queries.findSimilarOutcomesLike(description, limit.toLong())
                    .executeAsList()
                    .map { row -> mapRowToOutcomeMemory(row) }
            }
        }
    }

    override suspend fun getOutcomesByTicket(
        ticketId: TicketId,
    ): Result<List<OutcomeMemory>> = withContext(Dispatchers.IO) {
        runCatching {
            queries.getOutcomesByTicket(ticketId)
                .executeAsList()
                .map { row -> mapRowToOutcomeMemory(row) }
        }
    }

    override suspend fun getOutcomesByExecutor(
        executorId: ExecutorId,
        limit: Int,
    ): Result<List<OutcomeMemory>> = withContext(Dispatchers.IO) {
        runCatching {
            queries.getOutcomesByExecutor(executorId, limit.toLong())
                .executeAsList()
                .map { row -> mapRowToOutcomeMemory(row) }
        }
    }

    /**
     * Map a database row to an OutcomeMemory domain object.
     */
    private fun mapRowToOutcomeMemory(row: OutcomeMemoryStore): OutcomeMemory {
        return OutcomeMemory(
            id = row.id,
            ticketId = row.ticket_id,
            executorId = row.executor_id,
            approach = row.approach,
            success = row.success == 1L,
            executionDurationMs = row.execution_duration_ms,
            filesChanged = row.files_changed.toInt(),
            errorMessage = row.error_message,
            timestamp = Instant.fromEpochMilliseconds(row.timestamp),
        )
    }
}
