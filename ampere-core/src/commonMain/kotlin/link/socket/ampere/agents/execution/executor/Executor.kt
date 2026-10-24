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

    /**
     * The `Executor.id` that will perform [tool] when this executor is asked to [execute] it
     * (AMPR-491).
     *
     * [id] for every executor that does its own work, which is the default. A composite
     * executor — [RoutedExecutor], which sends each tool to the executor bound for it —
     * answers with the id of the executor it routes to, so that what `ToolExecutionEngine`
     * stamps on the tool-event pair, the dispatched context and the outcome row names the
     * performer (AMPR-443) rather than the router in front of it.
     */
    fun performerFor(tool: Tool<*>): ExecutorId = id
}
