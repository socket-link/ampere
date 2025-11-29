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
 * StdioMcpConnection - MCP connection using Standard Input/Output transport.
 *
 * This implementation communicates with a local MCP server process via stdin/stdout.
 * The server is spawned as a child process when connect() is called, and messages
 * are exchanged via:
 * - Requests written to the process's stdin
 * - Responses read from the process's stdout
 * - Errors/logs read from the process's stderr
 *
 * The protocol follows JSON-RPC 2.0 with newline-delimited messages:
 * - Each message is a single JSON object on one line
 * - Messages are separated by newlines
 *
 * Thread-safety: All public methods are protected by a mutex.
 *
 * Platform-specific implementation: The actual process spawning and I/O handling
 * is delegated to platform-specific code via StdioProcessHandler.
 */
class StdioMcpConnection(
    private val config: McpServerConfiguration,
    private val processHandler: StdioProcessHandler,
    private val logger: Logger = Logger.withTag("StdioMcpConnection"),
) : McpServerConnection {

    override val serverId: String = config.id

    private val mcpClient = McpClient()
    private val mutex = Mutex()

    override var isConnected: Boolean = false
        private set

    /**
     * Spawns the MCP server process and establishes I/O streams.
     */
    override suspend fun connect(): Result<Unit> = mutex.withLock {
        if (isConnected) {
            return Result.success(Unit)
        }

        logger.i { "Connecting to stdio MCP server: ${config.displayName} at ${config.endpoint}" }

        return processHandler.startProcess(config.endpoint).onSuccess {
            isConnected = true
            logger.i { "Successfully connected to ${config.displayName}" }
        }.onFailure { error ->
            logger.e(error) { "Failed to connect to ${config.displayName}" }
        }
    }

    /**
     * Performs the MCP initialize handshake via stdin/stdout.
     */
    override suspend fun initialize(): Result<InitializeResult> = mutex.withLock {
        check(isConnected) { "Must connect() before initialize()" }

        logger.i { "Initializing MCP handshake with ${config.displayName}..." }

        val request = mcpClient.createInitializeRequest()
        val requestJson = mcpClient.serializeRequest(request)

        return processHandler.sendMessage(requestJson)
            .mapCatching { processHandler.receiveMessage() }
            .mapCatching { responseJson ->
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
     * Queries available tools via stdio.
     */
    override suspend fun listTools(): Result<List<McpToolDescriptor>> = mutex.withLock {
        check(mcpClient.isInitialized) { "Must initialize() before listTools()" }

        logger.i { "Listing tools from ${config.displayName}..." }

        val request = mcpClient.createToolsListRequest()
        val requestJson = mcpClient.serializeRequest(request)

        return processHandler.sendMessage(requestJson)
            .mapCatching { processHandler.receiveMessage() }
            .mapCatching { responseJson ->
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
     * Invokes a tool via stdio.
     */
    override suspend fun invokeTool(
        toolName: String,
        arguments: JsonElement?,
    ): Result<ToolCallResult> = mutex.withLock {
        check(mcpClient.isInitialized) { "Must initialize() before invokeTool()" }

        logger.i { "Invoking tool '$toolName' on ${config.displayName}..." }

        val request = mcpClient.createToolCallRequest(toolName, arguments)
        val requestJson = mcpClient.serializeRequest(request)

        return processHandler.sendMessage(requestJson)
            .mapCatching { processHandler.receiveMessage() }
            .mapCatching { responseJson ->
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
     * Terminates the child process and cleans up streams.
     */
    override suspend fun disconnect(): Result<Unit> = mutex.withLock {
        if (!isConnected) {
            return Result.success(Unit)
        }

        logger.i { "Disconnecting from ${config.displayName}..." }

        return processHandler.stopProcess().onSuccess {
            isConnected = false
            mcpClient.reset()
            logger.i { "Successfully disconnected from ${config.displayName}" }
        }.onFailure { error ->
            logger.e(error) { "Error during disconnect from ${config.displayName}" }
        }
    }
}

/**
 * Platform-specific handler for stdio process management.
 *
 * This is an expect/actual interface - implementations differ per platform:
 * - JVM: Uses ProcessBuilder and Process I/O streams
 * - JS/Node: Uses child_process module
 * - Native: Uses platform-specific process APIs
 */
expect class StdioProcessHandler() {
    /**
     * Starts the MCP server process.
     *
     * @param executablePath Path to the executable
     * @return Result indicating success or failure
     */
    suspend fun startProcess(executablePath: String): Result<Unit>

    /**
     * Sends a message to the process via stdin.
     *
     * @param message The JSON message (single line, no trailing newline)
     * @return Result indicating success or failure
     */
    suspend fun sendMessage(message: String): Result<Unit>

    /**
     * Receives a message from the process via stdout.
     *
     * Blocks until a complete message is received (newline-delimited).
     *
     * @return The received JSON message
     * @throws Exception if read fails or process terminates
     */
    suspend fun receiveMessage(): String

    /**
     * Stops the process and cleans up resources.
     *
     * @return Result indicating success or failure
     */
    suspend fun stopProcess(): Result<Unit>
}
