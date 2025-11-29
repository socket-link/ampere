package link.socket.ampere.agents.tools.mcp.protocol

import kotlinx.serialization.json.Json
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.data.DEFAULT_JSON

/**
 * McpClient - Protocol-level client for communicating with MCP servers.
 *
 * This class handles the JSON-RPC 2.0 message framing and request/response
 * matching for the MCP protocol. It doesn't handle transport - that's delegated
 * to the connection layer (stdio, http, sse).
 *
 * Key responsibilities:
 * - Serialize requests to JSON
 * - Deserialize responses from JSON
 * - Generate unique request IDs
 * - Match responses to requests
 * - Validate protocol semantics (e.g., initialized before tool calls)
 *
 * The client maintains minimal state:
 * - Whether the connection is initialized
 * - The server info and capabilities (from initialize response)
 *
 * Thread-safety: This class is NOT thread-safe. Callers must synchronize access.
 */
class McpClient(
    private val json: Json = DEFAULT_JSON,
) {
    /**
     * Whether the MCP handshake has completed successfully.
     * Must be true before calling tools/list or tools/call.
     */
    var isInitialized: Boolean = false
        private set

    /**
     * Server information from the initialize response.
     * Null until initialize succeeds.
     */
    var serverInfo: ServerInfo? = null
        private set

    /**
     * Server capabilities from the initialize response.
     * Null until initialize succeeds.
     */
    var serverCapabilities: ServerCapabilities? = null
        private set

    /**
     * Creates an initialize request.
     *
     * This should be the first request sent after connecting to an MCP server.
     */
    fun createInitializeRequest(): McpRequest {
        return McpRequest(
            id = generateRequestId("init"),
            method = "initialize",
            params = json.encodeToJsonElement(
                InitializeParams.serializer(),
                InitializeParams(
                    clientInfo = ClientInfo(
                        name = "Ampere",
                        version = "1.0.0",
                    ),
                ),
            ),
        )
    }

    /**
     * Processes an initialize response.
     *
     * Validates the response and updates internal state if successful.
     *
     * @param response The response from the server
     * @return Result containing the InitializeResult or error
     */
    fun processInitializeResponse(response: McpResponse): Result<InitializeResult> {
        if (response.isError()) {
            return Result.failure(
                McpProtocolException(
                    "Initialize failed: ${response.error?.message}",
                    response.error,
                ),
            )
        }

        return try {
            val result = json.decodeFromJsonElement(
                InitializeResult.serializer(),
                response.result!!,
            )

            // Update state
            isInitialized = true
            serverInfo = result.serverInfo
            serverCapabilities = result.capabilities

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to parse initialize response: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    /**
     * Creates a tools/list request.
     *
     * This retrieves all tools exposed by the MCP server.
     *
     * @throws IllegalStateException if not initialized
     */
    fun createToolsListRequest(): McpRequest {
        check(isInitialized) { "Must initialize before calling tools/list" }

        return McpRequest(
            id = generateRequestId("tools-list"),
            method = "tools/list",
            params = null, // tools/list takes no parameters
        )
    }

    /**
     * Processes a tools/list response.
     *
     * @param response The response from the server
     * @return Result containing the ToolsListResult or error
     */
    fun processToolsListResponse(response: McpResponse): Result<ToolsListResult> {
        if (response.isError()) {
            return Result.failure(
                McpProtocolException(
                    "Tools list failed: ${response.error?.message}",
                    response.error,
                ),
            )
        }

        return try {
            val result = json.decodeFromJsonElement(
                ToolsListResult.serializer(),
                response.result!!,
            )

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to parse tools/list response: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    /**
     * Creates a tools/call request.
     *
     * This invokes a specific tool on the MCP server.
     *
     * @param toolName The name of the tool to invoke
     * @param arguments The tool's input arguments (JSON)
     * @throws IllegalStateException if not initialized
     */
    fun createToolCallRequest(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonElement?,
    ): McpRequest {
        check(isInitialized) { "Must initialize before calling tools" }

        return McpRequest(
            id = generateRequestId("tool-call-$toolName"),
            method = "tools/call",
            params = json.encodeToJsonElement(
                ToolCallParams.serializer(),
                ToolCallParams(
                    name = toolName,
                    arguments = arguments,
                ),
            ),
        )
    }

    /**
     * Processes a tools/call response.
     *
     * @param response The response from the server
     * @return Result containing the ToolCallResult or error
     */
    fun processToolCallResponse(response: McpResponse): Result<ToolCallResult> {
        if (response.isError()) {
            return Result.failure(
                McpProtocolException(
                    "Tool call failed: ${response.error?.message}",
                    response.error,
                ),
            )
        }

        return try {
            val result = json.decodeFromJsonElement(
                ToolCallResult.serializer(),
                response.result!!,
            )

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to parse tool call response: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    /**
     * Serializes an MCP request to JSON string.
     */
    fun serializeRequest(request: McpRequest): String {
        return json.encodeToString(McpRequest.serializer(), request)
    }

    /**
     * Deserializes a JSON string to an MCP response.
     */
    fun deserializeResponse(jsonString: String): Result<McpResponse> {
        return try {
            val response = json.decodeFromString(McpResponse.serializer(), jsonString)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(
                McpProtocolException(
                    "Failed to deserialize response: ${e.message}",
                    null,
                    e,
                ),
            )
        }
    }

    /**
     * Resets the client state (e.g., after disconnection).
     */
    fun reset() {
        isInitialized = false
        serverInfo = null
        serverCapabilities = null
    }

    /**
     * Generates a unique request ID.
     */
    private fun generateRequestId(prefix: String): String {
        return "${prefix}-${generateUUID(prefix)}"
    }
}

/**
 * Exception thrown when MCP protocol errors occur.
 *
 * @property message Human-readable error message
 * @property mcpError The MCP error details, if available
 * @property cause The underlying cause, if any
 */
class McpProtocolException(
    message: String,
    val mcpError: McpError? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
