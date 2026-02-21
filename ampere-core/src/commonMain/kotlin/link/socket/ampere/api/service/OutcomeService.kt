package link.socket.ampere.api.service

import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.api.model.OutcomeStats

/**
 * SDK service for execution history and outcome tracking.
 *
 * Maps to CLI commands: `outcomes ticket`, `outcomes search`, `outcomes stats`
 *
 * ```
 * val results = ampere.outcomes.search("authentication retry")
 * val stats = ampere.outcomes.stats()
 * println("${stats.successRate * 100}% success rate over ${stats.totalOutcomes} outcomes")
 * ```
 */
interface OutcomeService {

    /**
     * Get execution outcomes for a specific ticket.
     *
     * ```
     * val outcomes = ampere.outcomes.forTicket("ticket-123")
     * outcomes.forEach { println("${it.approach}: ${if (it.success) "OK" else it.errorMessage}") }
     * ```
     *
     * @param ticketId The ticket to get outcomes for
     */
    suspend fun forTicket(ticketId: TicketId): Result<List<OutcomeMemory>>

    /**
     * Search outcomes by similarity (semantic or keyword).
     *
     * ```
     * val results = ampere.outcomes.search("authentication retry", limit = 10)
     * ```
     *
     * @param query Search terms to match against past approaches
     * @param limit Maximum number of results to return
     */
    suspend fun search(query: String, limit: Int = 5): Result<List<OutcomeMemory>>

    /**
     * Get aggregate statistics about all execution outcomes.
     *
     * ```
     * val stats = ampere.outcomes.stats()
     * println("Success rate: ${stats.successRate * 100}%")
     * println("Average duration: ${stats.averageDurationMs}ms")
     * ```
     */
    suspend fun stats(): Result<OutcomeStats>

    /**
     * Get outcomes by a specific executor (agent).
     *
     * ```
     * val agentOutcomes = ampere.outcomes.byExecutor("engineer-agent")
     * ```
     *
     * @param executorId The executor (agent) to get outcomes for
     * @param limit Maximum number of results to return
     */
    suspend fun byExecutor(executorId: ExecutorId, limit: Int = 20): Result<List<OutcomeMemory>>
}
