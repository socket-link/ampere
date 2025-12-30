package link.socket.ampere.agents.execution.executor

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.health.ExecutorSystemHealth

typealias ExecutorId = String

/**
 * Base interface for all executor types in the system.
 *
 * Executors are the "muscle" abstraction - they perform work requested by agents
 * without the agent needing to know implementation details. Different executor
 * types handle different categories of work (code changes, deployments, etc.).
 *
 * This interface defines the common contract all executors share:
 * identification, capabilities, and health status.
 */
@Serializable
sealed interface Executor {

    /**
     * Unique identifier for this executor (e.g., "junie", "claude-code", "kubectl")
     */
    val id: ExecutorId

    /**
     * Human-readable name for UI display
     */
    val displayName: String

    /**
     * Capabilities this executor supports
     */
    val capabilities: ExecutorCapabilities

    /**
     * Check if this executor is properly configured and available.
     * Should be called before attempting to use the executor.
     */
    suspend fun performHealthCheck(): Result<ExecutorSystemHealth>

    /**
     * Execute a tool for a given task.
     * Returns a Flow to stream progress updates before the final result is emitted.
     */
    suspend fun execute(
        request: ExecutionRequest<*>,
        tool: Tool<*>,
    ): Flow<ExecutionStatus>
}
