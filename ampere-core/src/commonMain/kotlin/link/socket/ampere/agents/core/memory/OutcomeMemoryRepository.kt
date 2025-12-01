package link.socket.ampere.agents.core.memory

import kotlinx.datetime.Instant
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
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
     * @param executorId Which executor performed the work
     * @param approach Description of what was tried (from ticket + context)
     * @param outcome The execution outcome (success or failure)
     * @param timestamp When this execution completed
     * @return Result with the created OutcomeMemory or an error
     */
    suspend fun recordOutcome(
        ticketId: TicketId,
        executorId: ExecutorId,
        approach: String,
        outcome: ExecutionOutcome,
        timestamp: Instant,
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
     * @param executorId The executor to analyze
     * @param limit Maximum number of results
     * @return Result with recent outcomes from this executor
     */
    suspend fun getOutcomesByExecutor(
        executorId: ExecutorId,
        limit: Int = 20,
    ): Result<List<OutcomeMemory>>
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
)
