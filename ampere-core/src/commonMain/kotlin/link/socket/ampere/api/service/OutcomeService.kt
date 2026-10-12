package link.socket.ampere.api.service

import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.outcome.OutcomeMemory
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId
import link.socket.ampere.api.model.OutcomeStats

/**
 * SDK service for execution history and outcome tracking.
 *
 * Maps to CLI commands: `outcomes ticket`, `outcomes search`, `outcomes stats`,
 * `outcomes executor`, `outcomes actor`
 *
 * ```
 * val results = ampere.outcomes.search("authentication retry")
 * val stats = ampere.outcomes.stats()
 * println("${stats.successRate * 100}% success rate over ${stats.totalOutcomes} outcomes")
 * ```
 */
@link.socket.ampere.api.AmpereStableApi
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
     * Get outcomes performed by a specific executor — an `Executor.id` such as `function`, or
     * an Arc's `arc:<name>`.
     *
     * Not an agent (AMPR-443, F17): since 15.sqm an outcome names the agent that decided it
     * apart from the executor that performed it, and this filters by the second. Rows recorded
     * before then hold the agent's id here, because that is what was written; [byActor]
     * finds an agent's outcomes across both.
     *
     * ```
     * val functionOutcomes = ampere.outcomes.byExecutor("function")
     * ```
     *
     * @param executorId The executor to get outcomes for
     * @param limit Maximum number of results to return
     */
    suspend fun byExecutor(executorId: ExecutorId, limit: Int = 20): Result<List<OutcomeMemory>>

    /**
     * Get outcomes of the calls a specific agent decided (AMPR-443, F17), whichever executor
     * performed them — including rows recorded before the two were told apart.
     *
     * ```
     * val agentOutcomes = ampere.outcomes.byActor("engineer-agent")
     * ```
     *
     * Has a default that fails, so an implementation written before it existed still
     * compiles; the shipped service overrides it.
     *
     * @param actorId The agent to get outcomes for
     * @param limit Maximum number of results to return
     */
    suspend fun byActor(actorId: AgentId, limit: Int = 20): Result<List<OutcomeMemory>> =
        Result.failure(UnsupportedOperationException("${this::class.simpleName} does not implement byActor"))
}
