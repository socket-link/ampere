package link.socket.ampere.agents.execution.executor

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.errors.ExecutionError
import link.socket.ampere.agents.core.health.ExecutorSystemHealth
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.status.ExecutionStatus
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool

/**
 * FunctionExecutor - Executor implementation for locally-defined FunctionTools.
 *
 * This executor bridges the gap between agent execution requests and local function tools.
 * It wraps FunctionTool execution with proper ExecutionStatus event emission for observability.
 *
 * Key responsibilities:
 * 1. Validate that the tool is actually a FunctionTool
 * 2. Emit ExecutionStatus events throughout the process
 * 3. Invoke the FunctionTool's execute method
 * 4. Handle errors gracefully (no exceptions thrown from execute())
 * 5. Translate outcomes to appropriate ExecutionStatus events
 *
 * Unlike McpExecutor which deals with remote servers, FunctionExecutor deals with
 * in-process function calls. However, it still provides the same observability
 * and error handling patterns for consistency.
 *
 * Error handling philosophy:
 * - All failures are represented as ExecutionStatus.Failed or ExecutionOutcome.Failure
 * - Nothing throws exceptions from execute()
 * - Clear error messages guide users toward resolution
 *
 * @property logger Logger for diagnostic output
 */
@Serializable
class FunctionExecutor(
    override val id: ExecutorId,
    override val displayName: String,
    override val capabilities: ExecutorCapabilities,
    @kotlinx.serialization.Transient
    private val logger: Logger = Logger.withTag("FunctionExecutor"),
) : Executor {

    /**
     * Health check always succeeds for FunctionExecutor since it's in-process.
     */
    override suspend fun performHealthCheck(): Result<ExecutorSystemHealth> {
        return Result.success(
            ExecutorSystemHealth(
                version = "1.0.0",
                isAvailable = true,
                issues = emptyList(),
            )
        )
    }

    /**
     * Execute a FunctionTool for the given request.
     *
     * This method orchestrates the complete execution flow:
     * 1. Validates tool type (must be FunctionTool)
     * 2. Emits Started status
     * 3. Invokes the tool's execute method
     * 4. Emits Completed or Failed status based on outcome
     *
     * Returns a Flow that emits progress updates throughout execution.
     * The flow always completes with either Completed or Failed status.
     *
     * @param request The execution request with context and constraints
     * @param tool The tool to execute (must be a FunctionTool)
     * @return Flow of execution status updates
     */
    override suspend fun execute(
        request: ExecutionRequest<*>,
        tool: Tool<*>,
    ): Flow<ExecutionStatus> = flow {
        val startTime = Clock.System.now()

        // Validate tool type
        if (tool !is FunctionTool<*>) {
            logger.w { "Attempted to execute non-Function tool with FunctionExecutor: ${tool.name}" }
            emit(
                ExecutionStatus.Failed(
                    executorId = id,
                    timestamp = Clock.System.now(),
                    result = createFailureOutcome(
                        request = request as ExecutionRequest<ExecutionContext>,
                        startTime = startTime,
                        error = ExecutionError(
                            type = ExecutionError.Type.TOOL_UNAVAILABLE,
                            message = "Tool ${tool.name} is not a FunctionTool",
                            details = "FunctionExecutor can only execute FunctionTool instances. " +
                                "Tool type: ${tool::class.simpleName}",
                            isRetryable = false,
                        ),
                    ),
                )
            )
            return@flow
        }

        // Emit Started status
        emit(
            ExecutionStatus.Started(
                executorId = id,
                timestamp = Clock.System.now(),
            )
        )

        // Emit Planning status
        emit(
            ExecutionStatus.Planning(
                executorId = id,
                timestamp = Clock.System.now(),
                strategy = "Executing function tool '${tool.name}'",
            )
        )

        try {
            logger.i { "Executing FunctionTool '${tool.name}'" }

            // Execute the tool
            // Cast is safe because we validated tool is FunctionTool above
            @Suppress("UNCHECKED_CAST")
            val outcome = (tool as FunctionTool<ExecutionContext>).execute(
                request as ExecutionRequest<ExecutionContext>
            )

            // Emit appropriate status based on outcome type
            when (outcome) {
                is ExecutionOutcome.Success -> {
                    logger.i { "Successfully executed FunctionTool '${tool.name}'" }
                    emit(
                        ExecutionStatus.Completed(
                            executorId = id,
                            timestamp = Clock.System.now(),
                            result = outcome,
                        )
                    )
                }
                is ExecutionOutcome.Failure -> {
                    logger.w { "FunctionTool '${tool.name}' failed" }
                    emit(
                        ExecutionStatus.Failed(
                            executorId = id,
                            timestamp = Clock.System.now(),
                            result = outcome,
                        )
                    )
                }
                else -> {
                    logger.w { "Unexpected outcome type: ${outcome::class.simpleName}" }
                    emit(
                        ExecutionStatus.Failed(
                            executorId = id,
                            timestamp = Clock.System.now(),
                            result = createFailureOutcome(
                                request = request as ExecutionRequest<ExecutionContext>,
                                startTime = startTime,
                                error = ExecutionError(
                                    type = ExecutionError.Type.UNEXPECTED,
                                    message = "Unexpected outcome type",
                                    details = outcome::class.simpleName,
                                    isRetryable = false,
                                ),
                            ),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Catch any unexpected exceptions and convert to Failed status
            logger.e(e) { "Unexpected exception during FunctionTool execution" }
            emit(
                ExecutionStatus.Failed(
                    executorId = id,
                    timestamp = Clock.System.now(),
                    result = createFailureOutcome(
                        request = request as ExecutionRequest<ExecutionContext>,
                        startTime = startTime,
                        error = ExecutionError(
                            type = ExecutionError.Type.UNEXPECTED,
                            message = "Unexpected error: ${e.message}",
                            details = e.stackTraceToString(),
                            isRetryable = false,
                        ),
                    ),
                )
            )
        }
    }

    /**
     * Creates a failure outcome for an error.
     *
     * Helper function to consistently create failure outcomes across
     * different error scenarios.
     */
    private fun createFailureOutcome(
        request: ExecutionRequest<ExecutionContext>,
        startTime: kotlinx.datetime.Instant,
        error: ExecutionError,
    ): ExecutionOutcome.NoChanges.Failure {
        return ExecutionOutcome.NoChanges.Failure(
            executorId = id,
            ticketId = request.context.ticket.id,
            taskId = request.context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = Clock.System.now(),
            message = "${error.message}${error.details?.let { "\n\nDetails: $it" } ?: ""}",
        )
    }

    companion object {
        /**
         * Default function executor ID
         */
        const val DEFAULT_ID = "function"

        /**
         * Default display name
         */
        const val DEFAULT_DISPLAY_NAME = "Function Tool Executor"

        /**
         * Creates a default FunctionExecutor with standard configuration.
         */
        fun create(): FunctionExecutor {
            return FunctionExecutor(
                id = DEFAULT_ID,
                displayName = DEFAULT_DISPLAY_NAME,
                capabilities = ExecutorCapabilities(
                    supportsLanguages = emptySet(), // Function tools are language-agnostic
                    supportsFrameworks = emptySet(), // Function tools are framework-agnostic
                ),
            )
        }
    }
}
