package link.socket.ampere.agents.domain

/**
 * Agent-specific errors.
 *
 * These errors represent failures in agent cognitive processes, including
 * memory operations, planning failures, and execution errors.
 */
sealed class AgentError : Exception() {

    /**
     * Error during memory recall or storage operations.
     *
     * This can occur when:
     * - Memory service is not configured
     * - Database queries fail
     * - Knowledge retrieval/storage encounters errors
     *
     * @param message Human-readable error description
     * @param cause The underlying cause (can be Exception or other error types)
     */
    data class MemoryRecallFailure(
        override val message: String,
        override val cause: Throwable? = null,
    ) : AgentError() {
        // Secondary constructor for non-Throwable causes
        constructor(message: String, cause: Any?) : this(
            message = message,
            cause = cause as? Throwable,
        )
    }
}
