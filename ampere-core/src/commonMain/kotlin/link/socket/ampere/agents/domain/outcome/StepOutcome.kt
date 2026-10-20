@file:OptIn(ExperimentalSerializationApi::class)

package link.socket.ampere.agents.domain.outcome

import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Outcome of executing a single step in a plan.
 *
 * Tracks whether the step succeeded, partially succeeded, failed, is held waiting on a
 * person, or was skipped, along with relevant details and timestamps.
 *
 * Every variant also says where its text came from ([origin], AMPR-453). The field is
 * additive on the wire: it is never written when it is [ContentOrigin.External], so an
 * outcome that is not `Own` encodes exactly as it did before the field existed, and one
 * stored before then decodes as `External` — the safe reading of a result nobody marked.
 */
@Serializable
sealed interface StepOutcome : Outcome {
    val stepDescription: String
    val startTimestamp: Instant
    val endTimestamp: Instant

    /**
     * Whether this step's text — its details, its error, what it waits on, why it was
     * skipped — was produced by the run itself or came from outside it (AMPR-453).
     * `External` unless something proved otherwise; see [ContentOrigin].
     */
    val origin: ContentOrigin

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
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val origin: ContentOrigin = ContentOrigin.External,
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
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val origin: ContentOrigin = ContentOrigin.External,
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
     * @property tripped The rule that stopped the step, when a rule did (AMPR-472, F33):
     *   the Breaker tripped on it before the step ran. Null for every other failure — a
     *   tool that failed, an admission that could not be decided. [error] is unchanged
     *   either way, and is the trip's reason when there was one. Never written when null,
     *   so a failure without one encodes exactly as it did before the field existed.
     */
    @Serializable
    data class Failure(
        override val id: OutcomeId,
        override val stepDescription: String,
        override val startTimestamp: Instant,
        override val endTimestamp: Instant,
        val error: String,
        val isCritical: Boolean = true,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val origin: ContentOrigin = ContentOrigin.External,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val tripped: TrippedRule? = null,
    ) : StepOutcome

    /**
     * Step is waiting on a person (AMPR-425): its tool reported
     * [ExecutionOutcome.Held][link.socket.ampere.agents.domain.outcome.ExecutionOutcome.Held].
     *
     * Neither a success nor a failure. It stops the plan walk the way a critical failure
     * does — every later step is [Skipped] — because the steps after it would need an
     * output the held one has not produced.
     *
     * @property reason What is being waited on, for a person to read
     * @property reference The tool's opaque handle for the hold, carried and never read
     */
    @Serializable
    data class Held(
        override val id: OutcomeId,
        override val stepDescription: String,
        override val startTimestamp: Instant,
        override val endTimestamp: Instant,
        val reason: String,
        val reference: String? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val origin: ContentOrigin = ContentOrigin.External,
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
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val origin: ContentOrigin = ContentOrigin.External,
    ) : StepOutcome {
        override val startTimestamp: Instant get() = timestamp
        override val endTimestamp: Instant get() = timestamp
    }
}

/**
 * The rule a step was stopped by, on the step's [StepOutcome.Failure] (AMPR-472, F33).
 *
 * The same pair `PlanEvent.StepAdmitted` records as its `AdmissionRecord.Trip`, so a reader
 * holding only the outcome, or only `PlanStepCompleted`, knows the step was stopped by a
 * rule rather than by a fault, without matching the error's text.
 *
 * @property rule The rule's stable name, as `Breaker.ruleName` gives it.
 * @property reason The trip's reason, which is also the failure's error.
 */
@Serializable
data class TrippedRule(val rule: String, val reason: String)
