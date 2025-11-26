package link.socket.ampere.agents.core.errors

import kotlinx.serialization.Serializable

/** Details about why execution failed */
@Serializable
data class ExecutionError(
    /** Category of error */
    val type: Type,
    /** Human-readable error message */
    val message: String,
    /** Detailed information for debugging */
    val details: String? = null,
    /** Whether this error might succeed if retried */
    val isRetryable: Boolean = false,
) : Error {

    /** Categories of execution errors */
    enum class Type {
        /** Execution exceeded time limit */
        TIMEOUT,
        /** The underlying tool is not available or not configured */
        TOOL_UNAVAILABLE,
        /** Problem with the workspace (permissions, missing files, etc.) */
        WORKSPACE_ERROR,
        /** Code failed to compile */
        COMPILATION_FAILED,
        /** Tests failed */
        TESTS_FAILED,
        /** Unexpected error */
        UNEXPECTED;
    }
}
