package link.socket.ampere.agents.execution.outcomes

import kotlinx.serialization.Serializable

/**
 * Results from all validation steps.
 */
@Serializable
data class ExecutionValidationResults(
    /** Compilation results, null if compilation was not checked */
    val compilation: ExecutionCompilationValidation?,
    /** Linting results, null if linting was not run */
    val linting: ExecutionLintValidation?,
    /** Test results, null if tests were not run */
    val tests: ExecutionTestValidation?,
)
