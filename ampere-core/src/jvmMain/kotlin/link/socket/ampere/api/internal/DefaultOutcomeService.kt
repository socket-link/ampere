package link.socket.ampere.api.internal

import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.api.service.OutcomeService

internal class DefaultOutcomeService(
    private val outcomeRepository: OutcomeMemoryRepository,
) : OutcomeService {

    override suspend fun forTicket(ticketId: TicketId): Result<List<OutcomeMemory>> =
        outcomeRepository.getOutcomesByTicket(ticketId)

    override suspend fun search(query: String, limit: Int): Result<List<OutcomeMemory>> =
        outcomeRepository.findSimilarOutcomes(query, limit)

    override suspend fun byExecutor(executorId: ExecutorId, limit: Int): Result<List<OutcomeMemory>> =
        outcomeRepository.getOutcomesByExecutor(executorId, limit)
}
