package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable

/**
 * Constraints that bound the execution.
 * Prevents runaway executions and ensures quality standards.
 */
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
