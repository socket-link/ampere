package link.socket.ampere.agents.tools.mcp.connection

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 * - HttpMcpConnection: Remote servers over the Streamable HTTP transport
 *
 * The interface follows a lifecycle:
 * 1. connect() - Establish the underlying transport connection
 * 2. initialize() - Perform MCP handshake and exchange capabilities
 * 3. listTools() - Query available tools
 * 4. invokeTool() / invokeToolStreaming() - Execute tools (can be called multiple times)
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
     * For HTTP: Readies the transport and sends nothing. The Streamable HTTP
     * transport has no request that only tests reachability, so whether the
     * server can be reached is whatever initialize() reports.
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
     * Invokes a tool and reports on it while it runs (AMPR-515).
     *
     * The returned flow is cold: nothing is sent until it is collected, and
     * each collection is its own call. It yields the server's progress and log
     * messages about the call as they arrive and then exactly one terminal
     * event, [McpCallEvent.Completed] or [McpCallEvent.Failed]. It does not
     * throw for a failed call.
     *
     * What a connection that implements this does, beyond [invokeTool]:
     *
     * - **Asks for progress.** The request carries a progress token, which is
     *   what makes this the call to collect when something should be shown
     *   while it runs. [invokeTool] sends none.
     * - **Honours [options].** The call's time limit is the caller's choice.
     * - **Tells the server when the collector goes away.** Cancelling the
     *   collecting coroutine sends `notifications/cancelled` for the request
     *   before the cancellation completes, waiting a short, bounded time for it
     *   to be delivered. The same is sent when the time limit runs out.
     *   [invokeTool] does this too: it is the same call without the progress.
     *
     * The default here is for a connection with nothing to stream, which is
     * every fake and any implementation written before this existed: it runs
     * [invokeTool] and yields its outcome as the one terminal event, ignoring
     * [options]. That is also all a server that answers in a single JSON body
     * ever yields.
     *
     * @param toolName The name of the tool to invoke
     * @param arguments The tool's input arguments as JSON
     * @param options How this call differs from the connection's defaults
     */
    fun invokeToolStreaming(
        toolName: String,
        arguments: JsonElement?,
        options: McpCallOptions = McpCallOptions.Default,
    ): Flow<McpCallEvent> = flow {
        emit(terminalEventOf(invokeTool(toolName, arguments)))
    }

    /**
     * Closes the connection and cleans up resources.
     *
     * For STDIO: Terminates the child process
     * For HTTP: Ends the session, if the server issued one, and closes the HTTP client
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
open class McpConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The server refused the request's credential: it answered HTTP 401.
 *
 * Its own type because it is the one connection failure a caller can do
 * something about without a person: [link.socket.ampere.mcp.McpClient]
 * refreshes the credential once and retries once on it (AMPR-464). A 403 is
 * not this — the credential was accepted and the request is still not allowed,
 * so a fresh token would be refused the same way.
 *
 * @property wwwAuthenticate The 401's `WWW-Authenticate` header, when the
 *   server sent one. It is the server's own statement of how to obtain a
 *   credential (for an OAuth-protected server, where its resource metadata is),
 *   and carries no credential itself.
 */
class McpUnauthorizedException(
    message: String,
    val wwwAuthenticate: String? = null,
) : McpConnectionException(message)

/**
 * The server answered with an HTTP status that is neither a success nor one of
 * the statuses with a meaning of its own (401, and 404 on a session).
 *
 * @property status The status code, e.g. 406 for a request whose `Accept`
 *   header the server would not serve.
 */
class McpHttpStatusException(
    val status: Int,
    message: String,
) : McpConnectionException(message)

/**
 * The server ended the session, and a new one did not help (AMPR-514).
 *
 * A Streamable HTTP server answers 404 to a request that carries a session id
 * it no longer knows. The connection then opens a new session and sends the
 * request once more; this is what a caller sees when that second attempt was
 * refused the same way, or the new session could not be opened.
 */
class McpSessionExpiredException(
    message: String,
    cause: Throwable? = null,
) : McpConnectionException(message, cause)

/**
 * No response arrived, or was finished being read, within the request's time
 * limit.
 *
 * @property timeoutMs The limit that ran out.
 */
class McpRequestTimeoutException(
    val timeoutMs: Long,
) : McpConnectionException("The MCP server did not answer within $timeoutMs ms")

/**
 * The failure for a non-success HTTP [status]: [McpUnauthorizedException] for
 * a 401, [McpHttpStatusException] otherwise.
 *
 * @param action What was being attempted, e.g. "initialize".
 * @param wwwAuthenticate The response's `WWW-Authenticate` header, if any.
 */
internal fun httpStatusFailure(
    action: String,
    status: Int,
    wwwAuthenticate: String? = null,
): McpConnectionException {
    val message = "$action failed: $status ${HttpStatusCode.fromValue(status).description}".trimEnd()
    return if (status == HttpStatusCode.Unauthorized.value) {
        McpUnauthorizedException(message, wwwAuthenticate)
    } else {
        McpHttpStatusException(status, message)
    }
}
