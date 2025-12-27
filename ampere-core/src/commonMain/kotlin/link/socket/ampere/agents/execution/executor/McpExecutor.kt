package link.socket.ampere.agents.execution.executor

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.status.ExecutionStatus
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.health.ExecutorSystemHealth
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.tools.mcp.ServerManager
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult

/**
 * McpExecutor - Executor implementation for MCP (Model Context Protocol) tools.
 *
 * This executor bridges the gap between agent ExecutionRequests and MCP server
 * tool invocations. It handles the impedance matching between two abstractions:
 *
 * Agent side: ExecutionRequest -> ExecutionOutcome
 * MCP side: ToolCallParams -> ToolCallResult
 *
 * Key responsibilities:
 * 1. Validate that the tool is actually an McpTool
 * 2. Translate ExecutionRequest to MCP tool call arguments
 * 3. Get the MCP server connection from McpServerManager
 * 4. Invoke the tool on the MCP server
 * 5. Translate ToolCallResult back to ExecutionOutcome
 * 6. Emit ExecutionStatus events throughout the process
 * 7. Handle errors gracefully (no exceptions thrown from execute())
 *
 * This completes the metabolic loop - agents can now request external actions
 * and see results, which enables reflection and learning.
 *
 * Error handling philosophy:
 * - All failures are represented as ExecutionStatus.Failed or ExecutionOutcome.Failure
 * - Nothing throws exceptions from execute()
 * - Clear error messages guide users toward resolution
 * - Network timeouts, server unavailability, and protocol errors are all handled
 *
 * @property serverManager Manager for MCP server connections
 * @property eventBus Event bus for emitting execution status events
 * @property logger Logger for diagnostic output
 */
@Serializable
class McpExecutor(
    override val id: ExecutorId,
    override val displayName: String,
    override val capabilities: ExecutorCapabilities,
    @kotlinx.serialization.Transient
    private val serverManager: ServerManager? = null,
    @kotlinx.serialization.Transient
    private val eventBus: EventSerialBus? = null,
    @kotlinx.serialization.Transient
    private val logger: Logger = Logger.withTag("McpExecutor"),
) : Executor {

    /**
     * Health check verifies that:
     * 1. ServerManager is available
     * 2. At least one MCP server is connected and reachable
     *
     * This should be called before attempting to use the executor.
     */
    override suspend fun performHealthCheck(): Result<ExecutorSystemHealth> {
        if (serverManager == null) {
            return Result.success(
                ExecutorSystemHealth(
                    version = null,
                    isAvailable = false,
                    issues = listOf("ServerManager not configured"),
                ),
            )
        }

        // For now, we consider the executor healthy if the manager exists
        // Individual server availability is checked during execution
        return Result.success(
            ExecutorSystemHealth(
                version = "1.0.0",
                isAvailable = true,
                issues = emptyList(),
            ),
        )
    }

    /**
     * Execute an MCP tool for the given request.
     *
     * This method orchestrates the complete execution flow:
     * 1. Validates tool type (must be McpTool)
     * 2. Emits Started status
     * 3. Gets connection from manager
     * 4. Translates request to MCP arguments
     * 5. Invokes tool on server
     * 6. Translates result to outcome
     * 7. Emits Completed or Failed status
     *
     * Returns a Flow that emits progress updates throughout execution.
     * The flow always completes with either Completed or Failed status.
     *
     * @param request The execution request with context and constraints
     * @param tool The tool to execute (must be an McpTool)
     * @return Flow of execution status updates
     */
    override suspend fun execute(
        request: ExecutionRequest<*>,
        tool: Tool<*>,
    ): Flow<ExecutionStatus> = flow {
        val startTime = Clock.System.now()

        // Validate tool type
        if (tool !is McpTool) {
            logger.w { "Attempted to execute non-MCP tool with McpExecutor: ${tool.name}" }
            emit(
                ExecutionStatus.Failed(
                    executorId = id,
                    timestamp = Clock.System.now(),
                    result = createFailureOutcome(
                        request = request as ExecutionRequest<ExecutionContext>,
                        startTime = startTime,
                        error = ExecutionError(
                            type = ExecutionError.Type.TOOL_UNAVAILABLE,
                            message = "Tool ${tool.name} is not an MCP tool",
                            details = "McpExecutor can only execute McpTool instances. " +
                                "Tool type: ${tool::class.simpleName}",
                            isRetryable = false,
                        ),
                    ),
                ),
            )
            return@flow
        }

        // Emit Started status
        emit(
            ExecutionStatus.Started(
                executorId = id,
                timestamp = Clock.System.now(),
            ),
        )

        // Verify ServerManager is available
        if (serverManager == null) {
            logger.e { "ServerManager not configured" }
            emit(
                ExecutionStatus.Failed(
                    executorId = id,
                    timestamp = Clock.System.now(),
                    result = createFailureOutcome(
                        request = request as ExecutionRequest<ExecutionContext>,
                        startTime = startTime,
                        error = ExecutionError(
                            type = ExecutionError.Type.TOOL_UNAVAILABLE,
                            message = "MCP server manager not configured",
                            details = "McpExecutor requires ServerManager to be injected",
                            isRetryable = false,
                        ),
                    ),
                ),
            )
            return@flow
        }

        // Get connection from manager
        val connection = serverManager.getConnection(tool.serverId)
        if (connection == null) {
            logger.w { "MCP server '${tool.serverId}' is not connected" }
            emit(
                ExecutionStatus.Failed(
                    executorId = id,
                    timestamp = Clock.System.now(),
                    result = createFailureOutcome(
                        request = request as ExecutionRequest<ExecutionContext>,
                        startTime = startTime,
                        error = ExecutionError(
                            type = ExecutionError.Type.TOOL_UNAVAILABLE,
                            message = "MCP server '${tool.serverId}' is not connected",
                            details = "Ensure the server is configured and discovery has been run",
                            isRetryable = true,
                        ),
                    ),
                ),
            )
            return@flow
        }

        if (!connection.isConnected) {
            logger.w { "MCP server '${tool.serverId}' connection is not active" }
            emit(
                ExecutionStatus.Failed(
                    executorId = id,
                    timestamp = Clock.System.now(),
                    result = createFailureOutcome(
                        request = request as ExecutionRequest<ExecutionContext>,
                        startTime = startTime,
                        error = ExecutionError(
                            type = ExecutionError.Type.TOOL_UNAVAILABLE,
                            message = "MCP server '${tool.serverId}' connection is not active",
                            details = "The connection may have been disconnected. Try reconnecting.",
                            isRetryable = true,
                        ),
                    ),
                ),
            )
            return@flow
        }

        // Emit Planning status
        emit(
            ExecutionStatus.Planning(
                executorId = id,
                timestamp = Clock.System.now(),
                strategy = "Invoking MCP tool '${tool.name}' on server '${tool.serverId}'",
            ),
        )

        try {
            // Translate request to MCP arguments
            val arguments = translateRequestToMcpArguments(request)

            logger.i { "Invoking MCP tool '${tool.remoteToolName}' on server '${tool.serverId}'" }

            // Invoke tool on server
            val result = connection.invokeTool(
                toolName = tool.remoteToolName,
                arguments = arguments,
            )

            result.onSuccess { toolCallResult ->
                // Translate result to outcome
                val outcome = translateToolCallResultToOutcome(
                    toolCallResult = toolCallResult,
                    request = request as ExecutionRequest<ExecutionContext>,
                    startTime = startTime,
                )

                // Emit appropriate status based on outcome
                when (outcome) {
                    is ExecutionOutcome.Success -> {
                        logger.i { "Successfully executed MCP tool '${tool.name}'" }
                        emit(
                            ExecutionStatus.Completed(
                                executorId = id,
                                timestamp = Clock.System.now(),
                                result = outcome,
                            ),
                        )
                    }
                    is ExecutionOutcome.Failure -> {
                        logger.w { "MCP tool '${tool.name}' failed: ${(outcome as? ExecutionOutcome.NoChanges.Failure)?.message}" }
                        emit(
                            ExecutionStatus.Failed(
                                executorId = id,
                                timestamp = Clock.System.now(),
                                result = outcome,
                            ),
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
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                logger.e(error) { "Failed to invoke MCP tool '${tool.name}'" }
                emit(
                    ExecutionStatus.Failed(
                        executorId = id,
                        timestamp = Clock.System.now(),
                        result = createFailureOutcome(
                            request = request as ExecutionRequest<ExecutionContext>,
                            startTime = startTime,
                            error = ExecutionError(
                                type = ExecutionError.Type.UNEXPECTED,
                                message = "Tool invocation failed: ${error.message}",
                                details = error.stackTraceToString(),
                                isRetryable = true,
                            ),
                        ),
                    ),
                )
            }
        } catch (e: Exception) {
            // Catch any unexpected exceptions and convert to Failed status
            logger.e(e) { "Unexpected exception during MCP tool execution" }
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
                ),
            )
        }
    }

    /**
     * Translates an ExecutionRequest to MCP tool call arguments.
     *
     * This is the impedance matching layer - converting from agent abstractions
     * to MCP protocol format.
     *
     * The translation extracts:
     * - instructions: What the agent wants done
     * - task description: Context about the task
     * - Any additional context fields
     *
     * These are formatted as a JSON object that MCP servers can understand.
     *
     * @param request The execution request
     * @return JSON element containing the arguments
     */
    private fun translateRequestToMcpArguments(
        request: ExecutionRequest<*>,
    ): JsonElement {
        return buildJsonObject {
            // Extract instructions from context
            put("instructions", request.context.instructions)

            // Add task information
            val task = request.context.task
            put("taskId", task.id)
            // Task is a sealed interface - only some subtypes have description
            // Use local variable to enable smart casting
            if (task is Task.CodeChange) {
                put("taskDescription", task.description)
            }

            // Add ticket information
            put("ticketId", request.context.ticket.id)
            put("ticketDescription", request.context.ticket.description)

            // Add executor ID
            put("executorId", request.context.executorId)

            // Add any knowledge from past attempts
            if (request.context.knowledgeFromPastMemory.isNotEmpty()) {
                put(
                    "pastAttempts",
                    request.context.knowledgeFromPastMemory.size.toString(),
                )
            }
        }
    }

    /**
     * Translates a ToolCallResult to an ExecutionOutcome.
     *
     * This is the reverse impedance matching - converting from MCP protocol
     * format back to agent abstractions.
     *
     * The translation:
     * - Checks if the result indicates an error
     * - Extracts text content from ContentItems
     * - Creates appropriate Success or Failure outcome
     *
     * @param toolCallResult The MCP tool call result
     * @param request The original execution request
     * @param startTime When execution started
     * @return Execution outcome
     */
    private fun translateToolCallResultToOutcome(
        toolCallResult: ToolCallResult,
        request: ExecutionRequest<ExecutionContext>,
        startTime: kotlinx.datetime.Instant,
    ): ExecutionOutcome {
        val endTime = Clock.System.now()

        // Check if the MCP tool indicated an error
        if (toolCallResult.isError) {
            val errorMessage = toolCallResult.content
                .firstOrNull { it.type == "text" }
                ?.text
                ?: "Tool execution failed with unknown error"

            return ExecutionOutcome.NoChanges.Failure(
                executorId = id,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                message = errorMessage,
            )
        }

        // Extract result message from content
        val resultMessage = toolCallResult.content
            .filter { it.type == "text" }
            .joinToString("\n") { it.text ?: "" }
            .ifEmpty { "Tool executed successfully with no output" }

        return ExecutionOutcome.NoChanges.Success(
            executorId = id,
            ticketId = request.context.ticket.id,
            taskId = request.context.task.id,
            executionStartTimestamp = startTime,
            executionEndTimestamp = endTime,
            message = resultMessage,
        )
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
         * Default MCP executor ID
         */
        const val DEFAULT_ID = "mcp"

        /**
         * Default display name
         */
        const val DEFAULT_DISPLAY_NAME = "MCP Tool Executor"

        /**
         * Creates a default MCP executor with standard configuration.
         */
        fun create(
            serverManager: ServerManager,
            eventBus: EventSerialBus,
        ): McpExecutor {
            return McpExecutor(
                id = DEFAULT_ID,
                displayName = DEFAULT_DISPLAY_NAME,
                capabilities = ExecutorCapabilities(
                    supportsLanguages = emptySet(), // MCP tools are language-agnostic
                    supportsFrameworks = emptySet(), // MCP tools are framework-agnostic
                ),
                serverManager = serverManager,
                eventBus = eventBus,
            )
        }
    }
}
