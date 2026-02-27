package link.socket.ampere.agents.tools.mcp

import co.touchlab.kermit.Logger
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.tools.mcp.protocol.ContentItem

/**
 * McpToolExecutor - Bridges McpTool.execute() to MCP server connections.
 *
 * This class is the "motor cortex" for MCP tools — it takes the agent's intent
 * (execute this tool) and routes it through the appropriate server connection.
 *
 * It handles:
 * - Looking up the active connection for a tool's server
 * - Invoking the tool via the MCP protocol
 * - Converting MCP ToolCallResult to the internal Outcome type
 * - Graceful error handling for connection failures, server errors, and timeouts
 *
 * @property serverManager The manager that tracks active MCP server connections
 * @property logger Logger for observability
 */
class McpToolExecutor(
    private val serverManager: ServerManager,
    private val logger: Logger = Logger.withTag("McpToolExecutor"),
) {

    /**
     * Executes an MCP tool by routing through the appropriate server connection.
     *
     * Flow:
     * 1. Look up active connection for the tool's server
     * 2. Invoke the tool via the connection's invokeTool() method
     * 3. Convert the MCP ToolCallResult to an Outcome
     *
     * @param tool The MCP tool to execute
     * @param request The execution request containing context and constraints
     * @return Outcome describing success or failure
     */
    suspend fun execute(
        tool: McpTool,
        request: ExecutionRequest<ExecutionContext>,
    ): Outcome {
        val startTime = Clock.System.now()

        logger.i { "Executing MCP tool '${tool.remoteToolName}' on server '${tool.serverId}'..." }

        // Look up the active connection for this tool's server
        val connection = serverManager.getConnection(tool.serverId)
        if (connection == null) {
            val endTime = Clock.System.now()
            logger.w { "No active connection for server '${tool.serverId}'" }
            return ExecutionOutcome.NoChanges.Failure(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                message = "MCP server '${tool.serverId}' is not connected. " +
                    "The server may have disconnected or was never configured.",
            )
        }

        // Check connection is still alive
        if (!connection.isConnected) {
            val endTime = Clock.System.now()
            logger.w { "Connection to server '${tool.serverId}' is no longer active" }
            return ExecutionOutcome.NoChanges.Failure(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = startTime,
                executionEndTimestamp = endTime,
                message = "MCP server '${tool.serverId}' connection is no longer active.",
            )
        }

        // Invoke the tool via the MCP protocol
        val result = connection.invokeTool(
            toolName = tool.remoteToolName,
            arguments = tool.inputSchema,
        )

        val endTime = Clock.System.now()

        return result.fold(
            onSuccess = { toolCallResult ->
                if (toolCallResult.isError) {
                    logger.w {
                        "MCP tool '${tool.remoteToolName}' returned error: " +
                            extractTextContent(toolCallResult.content)
                    }
                    ExecutionOutcome.NoChanges.Failure(
                        executorId = request.context.executorId,
                        ticketId = request.context.ticket.id,
                        taskId = request.context.task.id,
                        executionStartTimestamp = startTime,
                        executionEndTimestamp = endTime,
                        message = "MCP tool '${tool.remoteToolName}' execution failed: " +
                            extractTextContent(toolCallResult.content),
                    )
                } else {
                    val responseText = extractTextContent(toolCallResult.content)
                    logger.i {
                        "MCP tool '${tool.remoteToolName}' executed successfully " +
                            "(${toolCallResult.content.size} content items)"
                    }
                    ExecutionOutcome.NoChanges.Success(
                        executorId = request.context.executorId,
                        ticketId = request.context.ticket.id,
                        taskId = request.context.task.id,
                        executionStartTimestamp = startTime,
                        executionEndTimestamp = endTime,
                        message = responseText,
                    )
                }
            },
            onFailure = { error ->
                logger.e(error) {
                    "MCP tool '${tool.remoteToolName}' invocation failed: ${error.message}"
                }
                ExecutionOutcome.NoChanges.Failure(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = startTime,
                    executionEndTimestamp = endTime,
                    message = "MCP tool invocation failed: ${error.message ?: "Unknown error"}",
                )
            },
        )
    }

    /**
     * Extracts text content from MCP content items.
     *
     * MCP tools return content as a list of ContentItem objects. This method
     * concatenates all text content items into a single string.
     */
    private fun extractTextContent(content: List<ContentItem>): String {
        return content
            .filter { it.type == "text" && it.text != null }
            .joinToString("\n") { it.text!! }
            .ifEmpty { "(no text content)" }
    }
}
