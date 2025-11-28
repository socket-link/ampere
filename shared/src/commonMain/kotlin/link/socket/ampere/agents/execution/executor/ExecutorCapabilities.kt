package link.socket.ampere.agents.execution.executor

import kotlinx.serialization.Serializable

/**
 * Defines what capabilities an executor supports.
 * Used for capability negotiation when selecting which executor to use.
 */
@Serializable
data class ExecutorCapabilities(
    /** Programming languages that this executor can work with */
    val supportsLanguages: Set<String>,
    /** Frameworks that this executor can work with */
    val supportsFrameworks: Set<String>,
)
