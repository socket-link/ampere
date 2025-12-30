package link.socket.ampere.agents.execution.executor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.health.ExecutorSystemHealth

/**
 * Instrumented executor for testing purposes.
 * Tracks whether execute() was called and delegates to a real executor.
 *
 * This is useful for verifying that agents invoke tools through executors
 * rather than calling tools directly, which is a critical architectural pattern.
 */
@Serializable
class InstrumentedExecutor(
    override val id: ExecutorId = "instrumented-executor",
    override val displayName: String = "Instrumented Executor",
    override val capabilities: ExecutorCapabilities = ExecutorCapabilities(
        supportsLanguages = setOf("Kotlin"),
        supportsFrameworks = setOf("Ktor"),
    ),
    @kotlinx.serialization.Transient
    private val delegateExecutor: Executor = FunctionExecutor.create(),
) : Executor {

    /**
     * Tracks whether execute() has been called on this executor.
     */
    @kotlinx.serialization.Transient
    var executorWasCalled = false
        private set

    /**
     * Number of times execute() has been called.
     */
    @kotlinx.serialization.Transient
    var executionCount = 0
        private set

    override suspend fun performHealthCheck(): Result<ExecutorSystemHealth> {
        return delegateExecutor.performHealthCheck()
    }

    override suspend fun execute(
        request: ExecutionRequest<*>,
        tool: Tool<*>,
    ): Flow<ExecutionStatus> {
        executorWasCalled = true
        executionCount++
        return delegateExecutor.execute(request, tool)
            .onEach { status ->
                // Could add additional instrumentation here if needed
            }
    }

    /**
     * Resets the instrumentation state.
     * Useful for running multiple tests with the same executor instance.
     */
    fun reset() {
        executorWasCalled = false
        executionCount = 0
    }
}
