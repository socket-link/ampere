package link.socket.ampere.agents.domain.outcome

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Outcome of executing a single step in a plan.
 *
 * Tracks whether the step succeeded, partially succeeded, failed, or was skipped,
 * along with relevant details and timestamps.
 */
@Serializable
sealed interface StepOutcome : Outcome {
    val stepDescription: String
    val startTimestamp: Instant
    val endTimestamp: Instant

    /**
     * Step completed successfully with all objectives met.
     *
     * @property stepId Unique identifier for the step
     * @property stepDescription Human-readable description of what the step did
     * @property startTimestamp When step execution began
     * @property endTimestamp When step execution completed
     * @property details Additional details about what was accomplished
     */
    @Serializable
    data class Success(
        override val id: OutcomeId,
        override val stepDescription: String,
        override val startTimestamp: Instant,
        override val endTimestamp: Instant,
        val details: String,
    ) : StepOutcome

    /**
     * Step completed with some objectives met but others failed.
     *
     * Used when a step has multiple sub-tasks and some succeed while others fail
     * (e.g., creating 5 issues where 3 succeed and 2 fail).
     *
     * @property stepId Unique identifier for the step
     * @property stepDescription Human-readable description of what the step attempted
     * @property startTimestamp When step execution began
     * @property endTimestamp When step execution completed
     * @property successCount Number of sub-tasks that succeeded
     * @property failureCount Number of sub-tasks that failed
     * @property details Additional details about what was partially accomplished
     */
    @Serializable
    data class PartialSuccess(
        override val id: OutcomeId,
        override val stepDescription: String,
        override val startTimestamp: Instant,
        override val endTimestamp: Instant,
        val successCount: Int,
        val failureCount: Int,
        val details: String,
    ) : StepOutcome

    /**
     * Step failed to complete its objectives.
     *
     * @property stepId Unique identifier for the step
     * @property stepDescription Human-readable description of what the step attempted
     * @property startTimestamp When step execution began
     * @property endTimestamp When step execution ended/failed
     * @property error Error message describing the failure
     * @property isCritical Whether this failure should halt the entire plan
     */
    @Serializable
    data class Failure(
        override val id: OutcomeId,
        override val stepDescription: String,
        override val startTimestamp: Instant,
        override val endTimestamp: Instant,
        val error: String,
        val isCritical: Boolean = true,
    ) : StepOutcome

    /**
     * Step was skipped due to a previous failure or unmet precondition.
     *
     * @property stepId Unique identifier for the step
     * @property stepDescription Human-readable description of what the step would have done
     * @property timestamp When the skip decision was made
     * @property reason Why the step was skipped
     */
    @Serializable
    data class Skipped(
        override val id: OutcomeId,
        override val stepDescription: String,
        val timestamp: Instant,
        val reason: String,
    ) : StepOutcome {
        override val startTimestamp: Instant get() = timestamp
        override val endTimestamp: Instant get() = timestamp
    }
}
