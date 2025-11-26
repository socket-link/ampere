package link.socket.ampere.agents.execution.outcomes

import kotlinx.serialization.Serializable

/** Results from code compilation */
@Serializable
data class ExecutionCompilationValidation(
    /** Whether compilation succeeded */
    val success: Boolean,
    /** Compilation errors if any */
    val errors: List<CompilationError>,
)

/** A single compilation error */
@Serializable
data class CompilationError(
    /** File where the error occurred */
    val file: String,
    /** Line number of the error */
    val line: Int,
    /** The error message */
    val message: String,
)
