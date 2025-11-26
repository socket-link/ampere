package link.socket.ampere.agents.core.health

import kotlinx.serialization.Serializable

/**
 * Health status of an executor.
 * Used to determine if an executor is available for use.
 */
@Serializable
data class ExecutorSystemHealth(
    /** Version of the underlying tool, if available */
    private val version: String?,
    /** Whether the executor is available and ready to accept requests */
    override val isAvailable: Boolean,
    /** List of issues that might affect executor functionality */
    val issues: List<String>,
) : SystemHealth {

    override val status: String = StringBuilder().apply {
        if (version != null) {
            append("($version) - ")
        }

        if (isAvailable) {
            append("Available: ")
        } else {
            append("Unavailable: ")
        }

        val allIssues = issues.joinToString(", ")

        append(allIssues)
    }.toString()
}
