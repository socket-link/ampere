package link.socket.ampere.agents.execution.results

import kotlinx.serialization.Serializable

/**
 * Results from all validation steps.
 */
@Serializable
data class ExecutionResult(
    /** Results from code changes validation, null if no changes were made */
    val codeChanges: ExecutionResultCodeChanges?,
    /** Compilation results, null if compilation was not checked */
    val compilation: ExecutionResultCompilation?,
    /** Linting results, null if linting was not run */
    val linting: ExecutionResultLint?,
    /** Test results, null if tests were not run */
    val tests: ExecutionResultTest?,
)
