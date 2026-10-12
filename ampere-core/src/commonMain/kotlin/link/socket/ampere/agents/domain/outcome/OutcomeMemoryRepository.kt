package link.socket.ampere.agents.domain.outcome

import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.events.tickets.TicketId
import link.socket.ampere.agents.execution.executor.ExecutorId

/**
 * Repository for storing execution outcomes to enable learning.
 *
 * This is episodic memory—recording what was tried, how, and what happened.
 * Each outcome is a data point. Individually they're just records, but
 * collectively they form the substrate's accumulated experience.
 *
 * The memory enables both debugging ("what happened when we tried this?")
 * and learning ("what approaches have worked for similar problems?").
 */
interface OutcomeMemoryRepository {

    /**
     * Record an execution outcome for future learning.
     *
     * This should be called after every execution attempt, whether it
     * succeeded or failed. Both outcomes are valuable—failures teach us
     * what doesn't work, which is just as important as knowing what does.
     *
     * @param ticketId Which ticket was being worked on
     * @param actorId Which agent decided the work (AMPR-443, F17) — null when no single agent
     *   did, as for an Arc run's own row. Required rather than defaulted, so a writer says
     *   which it means.
     * @param executorId Which `Executor.id` performed the work — never the agent's id
     * @param approach Description of what was tried (from ticket + context)
     * @param outcome The execution outcome (success or failure). A held outcome is
     *   neither, and is refused with a failed [Result] rather than recorded (AMPR-425).
     * @param timestamp When this execution completed
     * @param runId Optional Arc run correlation ID for trace projection
     * @return Result with the created OutcomeMemory or an error
     */
    suspend fun recordOutcome(
        ticketId: TicketId,
        actorId: AgentId?,
        executorId: ExecutorId,
        approach: String,
        outcome: ExecutionOutcome,
        timestamp: Instant,
        runId: RunId? = null,
    ): Result<OutcomeMemory>

    /**
     * Find past outcomes that might be relevant to a new situation.
     *
     * This is the learning query: "Before I try this, let me see if
     * anyone's done something similar and what happened."
     *
     * The similarity matching is deliberately fuzzy—we want to find
     * analogous situations even if they're not exact matches. A past
     * attempt at "add input validation to UserRepository" might inform
     * a current attempt at "add validation to OrderRepository".
     *
     * @param description The ticket description or search terms
     * @param limit Maximum number of results to return
     * @return Result with list of similar past outcomes, most recent first
     */
    suspend fun findSimilarOutcomes(
        description: String,
        limit: Int = 5,
    ): Result<List<OutcomeMemory>>

    /**
     * Get all outcomes for a specific ticket.
     *
     * Useful for debugging: "This ticket keeps failing, what have we tried?"
     * Shows the complete history of attempts for a single ticket.
     *
     * @param ticketId The ticket to query
     * @return Result with all outcomes for this ticket, most recent first
     */
    suspend fun getOutcomesByTicket(
        ticketId: TicketId,
    ): Result<List<OutcomeMemory>>

    /**
     * Get outcomes grouped by executor to analyze performance.
     *
     * This enables meta-learning: "Which executor tends to succeed at
     * which kinds of tasks?" Over time, this data can inform executor
     * selection strategy.
     *
     * Rows recorded before 15.sqm (AMPR-443) hold the agent's id here, because that is what
     * was written; a row recorded since holds the real executor. To ask what one agent did
     * across both, use [getOutcomesByActor].
     *
     * @param executorId The executor to analyze
     * @param limit Maximum number of results
     * @return Result with recent outcomes from this executor
     */
    suspend fun getOutcomesByExecutor(
        executorId: ExecutorId,
        limit: Int = 20,
    ): Result<List<OutcomeMemory>>

    /**
     * Get the outcomes of the calls one agent decided (AMPR-443, F17), whichever executor
     * performed them. Rows older than 15.sqm are found too: the migration copied the agent's
     * id they held into the actor column.
     *
     * Has an interface default that fails, the way `KnowledgeRepository.findKnowledgeVisibleTo`
     * does, so a repository written before the read existed still compiles and a read through
     * it is never mistaken for an agent with no outcomes.
     *
     * @param actorId The agent to query
     * @param limit Maximum number of results
     * @return Result with that agent's recent outcomes, most recent first
     */
    suspend fun getOutcomesByActor(
        actorId: AgentId,
        limit: Int = 20,
    ): Result<List<OutcomeMemory>> = Result.failure(
        UnsupportedOperationException("${this::class.simpleName} does not implement getOutcomesByActor"),
    )
}

/**
 * A stored memory of an execution outcome.
 *
 * This is the denormalized view for querying—it contains the key
 * information from an execution result in a form that's easy to
 * query and display.
 */
data class OutcomeMemory(
    val id: String,
    val ticketId: TicketId,

    /** The `Executor.id` that performed the work (the agent's id on a row older than 15.sqm). */
    val executorId: ExecutorId,

    /**
     * What approach was taken (from ticket description + additional context).
     * This is what we search when looking for similar outcomes.
     */
    val approach: String,

    /** Did execution succeed? */
    val success: Boolean,

    /** How long did it take? */
    val executionDurationMs: Long,

    /** How many files were changed? */
    val filesChanged: Int,

    /** If it failed, what was the error? */
    val errorMessage: String?,

    /** When did this happen? */
    val timestamp: Instant,

    /**
     * The agent that decided the work (AMPR-443, F17); null on a row no single agent decided,
     * such as an Arc run's own. Last, and defaulted, so a positional construction written
     * before the field existed still compiles.
     */
    val actorId: AgentId? = null,
)
