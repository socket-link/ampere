package link.socket.ampere.api.internal

import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.api.model.OutcomeStats
import link.socket.ampere.api.service.OutcomeService

internal class DefaultOutcomeService(
    private val outcomeRepository: OutcomeMemoryRepository,
) : OutcomeService {

    override suspend fun forTicket(ticketId: TicketId): Result<List<OutcomeMemory>> =
        outcomeRepository.getOutcomesByTicket(ticketId)

    override suspend fun search(query: String, limit: Int): Result<List<OutcomeMemory>> =
        outcomeRepository.findSimilarOutcomes(query, limit)

    override suspend fun stats(): Result<OutcomeStats> {
        return try {
            // OutcomeMemoryRepository lacks a dedicated count/stats query, so we
            // use a broad FTS5 match to retrieve all outcomes for aggregation.
            val allOutcomes = outcomeRepository.findSimilarOutcomes("", limit = 10000)
                .getOrElse { emptyList() }

            val total = allOutcomes.size
            val successes = allOutcomes.count { it.success }
            val failures = total - successes
            val avgDuration = if (allOutcomes.isNotEmpty()) {
                allOutcomes.map { it.executionDurationMs }.average().toLong()
            } else 0L

            Result.success(
                OutcomeStats(
                    totalOutcomes = total,
                    successCount = successes,
                    failureCount = failures,
                    successRate = if (total > 0) successes.toDouble() / total else 0.0,
                    averageDurationMs = avgDuration,
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun byExecutor(executorId: ExecutorId, limit: Int): Result<List<OutcomeMemory>> =
        outcomeRepository.getOutcomesByExecutor(executorId, limit)
}
