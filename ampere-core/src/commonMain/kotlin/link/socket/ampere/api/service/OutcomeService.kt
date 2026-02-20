package link.socket.ampere.api.service

import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId

/**
 * SDK service for execution history and outcome tracking.
 *
 * Maps to CLI commands: `outcomes ticket`, `outcomes search`, `outcomes executor`
 */
interface OutcomeService {

    /**
     * Get execution outcomes for a specific ticket.
     *
     * ```
     * ampere.outcomes.forTicket("ticket-123")
     * ```
     */
    suspend fun forTicket(ticketId: TicketId): Result<List<OutcomeMemory>>

    /**
     * Search outcomes by similarity (semantic or keyword).
     *
     * ```
     * ampere.outcomes.search("authentication retry", limit = 10)
     * ```
     */
    suspend fun search(query: String, limit: Int = 5): Result<List<OutcomeMemory>>

    /**
     * Get outcomes by a specific executor (agent).
     */
    suspend fun byExecutor(executorId: ExecutorId, limit: Int = 20): Result<List<OutcomeMemory>>
}
