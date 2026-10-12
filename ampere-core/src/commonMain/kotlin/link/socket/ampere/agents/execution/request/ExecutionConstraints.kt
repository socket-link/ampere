package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable

/**
 * Constraints that bound the execution.
 *
 * Retired by AMPR-445 (F14). Nothing ever read these fields, so no execution was ever bound
 * by them. Each one now has a home that is enforced or routed:
 *
 * - [timeoutMinutes] → `Rule.Quota.Time`, set on a hosted run as `RunQuotas.wallClock`.
 * - [maxFilesChanged] → `Rule.Quota.Count`. A run's step count is the only count the Breaker
 *   reads today (`RunQuotas.steps`).
 * - [requireTests], [requireLinting], [allowBreakingChanges] → `Rule.Requirements`. These
 *   are post-conditions, so verification checks them, not the Breaker.
 *
 * Kept, defaulted on `ExecutionRequest.constraints`, for one release so callers that still
 * pass one compile. It is deleted after that.
 */
@Deprecated(
    message = "Read by nothing. Bound a run with RunPolicy.quotas, which become Hard Rule.Quota rules " +
        "the Breaker admits each step against; Rule.Requirements belongs to verification (AMPR-445). " +
        "Deleted one release after AMPR-445.",
)
@Serializable
data class ExecutionConstraints(
    /** Maximum time the executor should spend on this task */
    val timeoutMinutes: Int = 30,
    /** Maximum number of files that can be changed, null for unlimited */
    val maxFilesChanged: Int? = null,
    /** Whether to require tests to pass before considering execution successful */
    val requireTests: Boolean = true,
    /** Whether to require linting to pass before considering execution successful */
    val requireLinting: Boolean = true,
    /** Whether the executor is allowed to make breaking API changes */
    val allowBreakingChanges: Boolean = false,
)
