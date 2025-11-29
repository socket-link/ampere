package link.socket.ampere.agents.tools.mcp.connection

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpClient
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult

/**
 * HttpMcpConnection - MCP connection using HTTP transport.
 *
 * This implementation communicates with a remote MCP server via HTTP POST requests.
 * Each MCP request is sent as an HTTP POST with a JSON-RPC body, and the response
 * is the JSON-RPC response.
 *
 * Features:
 * - Uses HTTP POST for all MCP requests
 * - Supports authentication via Authorization header (Bearer token)
 * - Configurable timeouts
 * - Connection pooling and keep-alive (delegated to HTTP client)
 *
 * Thread-safety: All public methods are protected by a mutex.
 *
 * Platform-specific implementation: The actual HTTP client is delegated to
 * platform-specific code via HttpClientHandler.
 */
class HttpMcpConnection(
    private val config: McpServerConfiguration,
    private val httpHandler: HttpClientHandler,
    private val logger: Logger = Logger.withTag("HttpMcpConnection"),
) : McpServerConnection {

    override val serverId: String = config.id

    private val mcpClient = McpClient()
    private val mutex = Mutex()

    override var isConnected: Boolean = false
        private set

    /**
     * Validates that the HTTP endpoint is accessible.
     */
    override suspend fun connect(): Result<Unit> = mutex.withLock {
        if (isConnected) {
            return Result.success(Unit)
        }

        logger.i { "Connecting to HTTP MCP server: ${config.displayName} at ${config.endpoint}" }

        return httpHandler.validateEndpoint(
            url = config.endpoint,
            authToken = config.authToken,
            timeoutMs = config.timeoutMs,
        ).onSuccess {
            isConnected = true
            logger.i { "Successfully connected to ${config.displayName}" }
        }.onFailure { error ->
            logger.e(error) { "Failed to connect to ${config.displayName}" }
        }
    }

    /**
     * Performs the MCP initialize handshake via HTTP POST.
     */
    override suspend fun initialize(): Result<InitializeResult> = mutex.withLock {
        check(isConnected) { "Must connect() before initialize()" }

        logger.i { "Initializing MCP handshake with ${config.displayName}..." }

        val request = mcpClient.createInitializeRequest()
        val requestJson = mcpClient.serializeRequest(request)

        return httpHandler.sendRequest(
            url = config.endpoint,
            body = requestJson,
            authToken = config.authToken,
            timeoutMs = config.timeoutMs,
        ).mapCatching { responseJson ->
            val response = mcpClient.deserializeResponse(responseJson).getOrThrow()
            mcpClient.processInitializeResponse(response).getOrThrow()
        }.onSuccess { result ->
            logger.i {
                "MCP handshake successful with ${config.displayName}: " +
                    "${result.serverInfo.name} v${result.serverInfo.version}"
            }
        }.onFailure { error ->
            logger.e(error) { "MCP handshake failed with ${config.displayName}" }
        }
    }

    /**
     * Queries available tools via HTTP POST.
     */
    override suspend fun listTools(): Result<List<McpToolDescriptor>> = mutex.withLock {
        check(mcpClient.isInitialized) { "Must initialize() before listTools()" }

        logger.i { "Listing tools from ${config.displayName}..." }

        val request = mcpClient.createToolsListRequest()
        val requestJson = mcpClient.serializeRequest(request)

        return httpHandler.sendRequest(
            url = config.endpoint,
            body = requestJson,
            authToken = config.authToken,
            timeoutMs = config.timeoutMs,
        ).mapCatching { responseJson ->
            val response = mcpClient.deserializeResponse(responseJson).getOrThrow()
            mcpClient.processToolsListResponse(response).getOrThrow()
        }.map { result ->
            logger.i { "Discovered ${result.tools.size} tools from ${config.displayName}" }
            result.tools
        }.onFailure { error ->
            logger.e(error) { "Failed to list tools from ${config.displayName}" }
        }
    }

    /**
     * Invokes a tool via HTTP POST.
     */
    override suspend fun invokeTool(
        toolName: String,
        arguments: JsonElement?,
    ): Result<ToolCallResult> = mutex.withLock {
        check(mcpClient.isInitialized) { "Must initialize() before invokeTool()" }

        logger.i { "Invoking tool '$toolName' on ${config.displayName}..." }

        val request = mcpClient.createToolCallRequest(toolName, arguments)
        val requestJson = mcpClient.serializeRequest(request)

        return httpHandler.sendRequest(
            url = config.endpoint,
            body = requestJson,
            authToken = config.authToken,
            timeoutMs = config.timeoutMs,
        ).mapCatching { responseJson ->
            val response = mcpClient.deserializeResponse(responseJson).getOrThrow()
            mcpClient.processToolCallResponse(response).getOrThrow()
        }.onSuccess { result ->
            logger.i {
                "Tool '$toolName' execution completed " +
                    "(${result.content.size} content items, error=${result.isError})"
            }
        }.onFailure { error ->
            logger.e(error) { "Tool '$toolName' execution failed" }
        }
    }

    /**
     * Closes the HTTP client and cleans up resources.
     */
    override suspend fun disconnect(): Result<Unit> = mutex.withLock {
        if (!isConnected) {
            return Result.success(Unit)
        }

        logger.i { "Disconnecting from ${config.displayName}..." }

        return httpHandler.close().onSuccess {
            isConnected = false
            mcpClient.reset()
            logger.i { "Successfully disconnected from ${config.displayName}" }
        }.onFailure { error ->
            logger.e(error) { "Error during disconnect from ${config.displayName}" }
        }
    }
}

/**
 * Platform-specific handler for HTTP communication.
 *
 * This is an expect/actual interface - implementations differ per platform:
 * - JVM: Uses Ktor client or java.net.http
 * - JS: Uses fetch or XMLHttpRequest
 * - Native: Uses platform-specific HTTP APIs
 */
expect class HttpClientHandler() {
    /**
     * Validates that the endpoint is accessible (e.g., via HEAD or GET).
     *
     * @param url The endpoint URL
     * @param authToken Optional authentication token
     * @param timeoutMs Request timeout in milliseconds
     * @return Result indicating success or failure
     */
    suspend fun validateEndpoint(
        url: String,
        authToken: String?,
        timeoutMs: Long,
    ): Result<Unit>

    /**
     * Sends an HTTP POST request with JSON body.
     *
     * @param url The endpoint URL
     * @param body The JSON request body
     * @param authToken Optional authentication token
     * @param timeoutMs Request timeout in milliseconds
     * @return Result containing the response body or error
     */
    suspend fun sendRequest(
        url: String,
        body: String,
        authToken: String?,
        timeoutMs: Long,
    ): Result<String>

    /**
     * Closes the HTTP client and releases resources.
     *
     * @return Result indicating success or failure
     */
    suspend fun close(): Result<Unit>
}
