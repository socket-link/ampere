package link.socket.ampere.api.service.stub

import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.api.model.OutcomeStats
import link.socket.ampere.api.service.OutcomeService

/**
 * Stub implementation of [OutcomeService] for testing and parallel development.
 *
 * Returns empty results and zeroed statistics.
 */
class StubOutcomeService : OutcomeService {

    override suspend fun forTicket(ticketId: TicketId): Result<List<OutcomeMemory>> =
        Result.success(emptyList())

    override suspend fun search(query: String, limit: Int): Result<List<OutcomeMemory>> =
        Result.success(emptyList())

    override suspend fun stats(): Result<OutcomeStats> =
        Result.success(
            OutcomeStats(
                totalOutcomes = 0,
                successCount = 0,
                failureCount = 0,
                successRate = 0.0,
                averageDurationMs = 0,
            )
        )

    override suspend fun byExecutor(executorId: ExecutorId, limit: Int): Result<List<OutcomeMemory>> =
        Result.success(emptyList())
}
