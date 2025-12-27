package link.socket.ampere.agents.tools.mcp.connection

import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult

/**
 * McpServerConnection - Abstract interface for MCP server connections.
 *
 * This interface abstracts the transport mechanism used to communicate with
 * an MCP server. Different implementations handle different protocols:
 * - StdioMcpConnection: Local executables communicating via stdin/stdout
 * - HttpMcpConnection: Remote servers using HTTP JSON-RPC
 * - SseMcpConnection: Server-sent events for streaming
 *
 * The interface follows a lifecycle:
 * 1. connect() - Establish the underlying transport connection
 * 2. initialize() - Perform MCP handshake and exchange capabilities
 * 3. listTools() - Query available tools
 * 4. invokeTool() - Execute tools (can be called multiple times)
 * 5. disconnect() - Clean up and close the connection
 *
 * All methods return Result<T> for explicit error handling.
 * Connection implementations must be thread-safe for concurrent calls.
 */
interface McpServerConnection {
    /**
     * The unique identifier for the MCP server this connection is for.
     */
    val serverId: String

    /**
     * Whether the connection is currently established.
     */
    val isConnected: Boolean

    /**
     * Establishes the underlying transport connection to the MCP server.
     *
     * For STDIO: Spawns the child process
     * For HTTP: Validates endpoint accessibility
     * For SSE: Opens the event stream
     *
     * This does NOT perform the MCP initialize handshake - that's done in initialize().
     *
     * @return Result indicating success or failure
     */
    suspend fun connect(): Result<Unit>

    /**
     * Performs the MCP initialize handshake.
     *
     * Sends an "initialize" request with client info and capabilities,
     * receives server info and capabilities in response.
     *
     * Must be called after connect() and before listTools()/invokeTool().
     *
     * @return Result containing the InitializeResult or error
     */
    suspend fun initialize(): Result<InitializeResult>

    /**
     * Queries the MCP server for its available tools.
     *
     * Sends a "tools/list" request and receives the list of tool descriptors.
     *
     * Must be called after initialize().
     *
     * @return Result containing the list of tool descriptors or error
     */
    suspend fun listTools(): Result<List<McpToolDescriptor>>

    /**
     * Invokes a specific tool on the MCP server.
     *
     * Sends a "tools/call" request with the tool name and arguments,
     * receives the execution result.
     *
     * Must be called after initialize().
     *
     * @param toolName The name of the tool to invoke
     * @param arguments The tool's input arguments as JSON
     * @return Result containing the tool execution result or error
     */
    suspend fun invokeTool(
        toolName: String,
        arguments: JsonElement?,
    ): Result<ToolCallResult>

    /**
     * Closes the connection and cleans up resources.
     *
     * For STDIO: Terminates the child process
     * For HTTP: Closes the HTTP client
     * For SSE: Closes the event stream
     *
     * After disconnect(), the connection should be ready for connect() again
     * (though a new instance is typically created instead).
     *
     * @return Result indicating success or failure
     */
    suspend fun disconnect(): Result<Unit>
}

/**
 * Base exception for connection-level errors.
 *
 * This is distinct from McpProtocolException (protocol-level errors).
 * Connection errors are about transport failures (network, process crashes, etc.)
 * while protocol errors are about invalid MCP messages or semantics.
 */
class McpConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
