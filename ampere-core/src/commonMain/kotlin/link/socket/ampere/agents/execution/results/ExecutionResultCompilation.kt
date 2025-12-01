package link.socket.ampere.agents.execution.results

import kotlinx.serialization.Serializable

/** Results from code compilation */
@Serializable
data class ExecutionResultCompilation(
    /** Whether compilation succeeded */
    val success: Boolean,
    /** Compilation errors if any */
    val errors: List<CompilationErrorResult>,
)

/** A single compilation error */
@Serializable
data class CompilationErrorResult(
    /** File where the error occurred */
    val file: String,
    /** Line number of the error */
    val line: Int,
    /** The error message */
    val message: String,
)
