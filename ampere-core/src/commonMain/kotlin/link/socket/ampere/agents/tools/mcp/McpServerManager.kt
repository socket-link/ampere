package link.socket.ampere.agents.tools.mcp

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.tools.McpServerId
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.tools.mcp.connection.HttpClientHandler
import link.socket.ampere.agents.tools.mcp.connection.HttpMcpConnection
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.connection.StdioMcpConnection
import link.socket.ampere.agents.tools.mcp.connection.StdioProcessHandler
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.registry.ToolRegistry

/**
 * McpServerManager - Manages the lifecycle of MCP servers and tool discovery.
 *
 * This is the "sensory receptor integration layer" that connects the agent system
 * to external MCP servers. It handles:
 * - Server configuration and connection setup
 * - Discovery (connecting to servers and querying their capabilities)
 * - Tool registration (converting MCP tools to internal McpTool types)
 * - Connection lifecycle (maintaining active connections, handling disconnects)
 * - Event emission (for observability into the discovery process)
 *
 * Key responsibilities:
 * 1. Accept server configurations via addServerConfiguration()
 * 2. Connect to all configured servers during discoverAndRegisterTools()
 * 3. For each successful connection:
 *    - Perform MCP handshake (initialize)
 *    - Query available tools (tools/list)
 *    - Register tools in the ToolRegistry
 *    - Emit ToolDiscoveryCompleteEvent for that server
 * 4. Track active connections for tool execution
 * 5. Handle disconnections and cleanup
 *
 * Failure handling:
 * - Individual server failures don't crash the system
 * - Warnings are logged for unreachable servers
 * - Other servers continue to be discovered
 * - Graceful degradation ensures maximum availability
 *
 * Thread-safety: All public methods are protected by a mutex.
 */
class McpServerManager(
    private val toolRegistry: ToolRegistry,
    private val eventBus: EventSerialBus,
    private val eventSource: EventSource,
    private val logger: Logger = Logger.withTag("McpServerManager"),
) : ServerManager {
    // Server configurations indexed by server ID
    private val serverConfigs = mutableMapOf<McpServerId, McpServerConfiguration>()

    // Active server connections indexed by server ID
    private val activeConnections = mutableMapOf<McpServerId, McpServerConnection>()

    private val mutex = Mutex()

    /**
     * Adds an MCP server configuration.
     *
     * This should be called before discoverAndRegisterTools() to register
     * servers that should be contacted during discovery.
     *
     * @param config The server configuration
     * @return Result indicating success or failure
     */
    suspend fun addServerConfiguration(config: McpServerConfiguration): Result<Unit> = mutex.withLock {
        logger.i { "Adding MCP server configuration: ${config.displayName} (${config.id})" }

        if (serverConfigs.containsKey(config.id)) {
            logger.w { "Server configuration already exists for ${config.id}, replacing..." }
        }

        serverConfigs[config.id] = config
        Result.success(Unit)
    }

    /**
     * Discovers and registers tools from all configured MCP servers.
     *
     * This is the main entry point for tool discovery. It:
     * 1. Iterates through all configured servers
     * 2. For each server:
     *    - Creates the appropriate connection type (stdio/http)
     *    - Connects and initializes
     *    - Lists available tools
     *    - Registers tools in the ToolRegistry
     *    - Emits discovery complete event
     * 3. Returns statistics about the discovery process
     *
     * Individual server failures are logged but don't stop discovery of other servers.
     *
     * @return Result containing discovery statistics
     */
    suspend fun discoverAndRegisterTools(): Result<McpDiscoveryResult> {
        logger.i { "Starting MCP tool discovery for ${serverConfigs.size} configured servers..." }

        var successfulServers = 0
        var failedServers = 0
        var totalToolsDiscovered = 0
        val serverResults = mutableMapOf<McpServerId, ServerDiscoveryResult>()

        // Process each configured server
        for ((serverId, config) in serverConfigs) {
            try {
                logger.i { "Discovering tools from: ${config.displayName} (${config.id})" }

                // Discover tools from this server
                val result = discoverServerTools(config)

                result.onSuccess { serverResult ->
                    successfulServers++
                    totalToolsDiscovered += serverResult.toolsDiscovered
                    serverResults[serverId] = serverResult

                    logger.i {
                        "✓ Successfully discovered ${serverResult.toolsDiscovered} tools " +
                            "from ${config.displayName}"
                    }

                    // Emit server-specific discovery complete event
                    emitServerDiscoveryComplete(serverId, config.displayName, serverResult.toolsDiscovered)
                }.onFailure { error ->
                    failedServers++
                    serverResults[serverId] = ServerDiscoveryResult(
                        serverId = serverId,
                        serverName = config.displayName,
                        toolsDiscovered = 0,
                        success = false,
                        error = error.message,
                    )

                    logger.w(error) { "✗ Failed to discover tools from ${config.displayName}" }
                }
            } catch (e: Exception) {
                failedServers++
                serverResults[serverId] = ServerDiscoveryResult(
                    serverId = serverId,
                    serverName = config.displayName,
                    toolsDiscovered = 0,
                    success = false,
                    error = e.message,
                )

                logger.w(e) { "✗ Exception during discovery from ${config.displayName}" }
            }
        }

        val discoveryResult = McpDiscoveryResult(
            totalServers = serverConfigs.size,
            successfulServers = successfulServers,
            failedServers = failedServers,
            totalToolsDiscovered = totalToolsDiscovered,
            serverResults = serverResults,
        )

        logger.i {
            "MCP tool discovery complete: " +
                "$successfulServers successful, $failedServers failed, " +
                "$totalToolsDiscovered tools discovered"
        }

        return Result.success(discoveryResult)
    }

    /**
     * Discovers tools from a single MCP server.
     *
     * Creates connection, connects, initializes, lists tools, and registers them.
     *
     * @param config The server configuration
     * @return Result containing discovery statistics for this server
     */
    private suspend fun discoverServerTools(
        config: McpServerConfiguration,
    ): Result<ServerDiscoveryResult> = mutex.withLock {
        // Create the appropriate connection type
        val connection = createConnection(config)

        // Connect to the server
        connection.connect().getOrElse { error ->
            return Result.failure(
                McpDiscoveryException(
                    "Failed to connect to ${config.displayName}: ${error.message}",
                    error,
                ),
            )
        }

        // Perform MCP handshake
        val initResult = connection.initialize().getOrElse { error ->
            connection.disconnect()
            return Result.failure(
                McpDiscoveryException(
                    "Failed to initialize ${config.displayName}: ${error.message}",
                    error,
                ),
            )
        }

        logger.i {
            "MCP handshake completed with ${config.displayName}: " +
                "${initResult.serverInfo.name} v${initResult.serverInfo.version}"
        }

        // List available tools
        val tools = connection.listTools().getOrElse { error ->
            connection.disconnect()
            return Result.failure(
                McpDiscoveryException(
                    "Failed to list tools from ${config.displayName}: ${error.message}",
                    error,
                ),
            )
        }

        logger.i { "Retrieved ${tools.size} tools from ${config.displayName}" }

        // Register tools in the registry
        var registeredCount = 0
        tools.forEach { toolDescriptor ->
            try {
                val mcpTool = convertToMcpTool(config, toolDescriptor)
                toolRegistry.registerTool(mcpTool).onSuccess {
                    registeredCount++
                }.onFailure { error ->
                    logger.w(error) {
                        "Failed to register tool ${toolDescriptor.name} from ${config.displayName}"
                    }
                }
            } catch (e: Exception) {
                logger.w(e) {
                    "Exception while registering tool ${toolDescriptor.name} from ${config.displayName}"
                }
            }
        }

        // Store active connection for later use (tool execution)
        activeConnections[config.id] = connection

        return Result.success(
            ServerDiscoveryResult(
                serverId = config.id,
                serverName = config.displayName,
                toolsDiscovered = registeredCount,
                success = true,
                error = null,
            ),
        )
    }

    /**
     * Converts an MCP tool descriptor to our internal McpTool type.
     */
    private fun convertToMcpTool(
        config: McpServerConfiguration,
        descriptor: McpToolDescriptor,
    ): McpTool {
        // Create namespaced tool ID: serverId:toolName
        val toolId = "${config.id}:${descriptor.name}"

        return McpTool(
            id = toolId,
            name = descriptor.name,
            description = descriptor.description,
            requiredAgentAutonomy = config.requiredAgentAutonomy,
            serverId = config.id,
            remoteToolName = descriptor.name,
            inputSchema = descriptor.inputSchema,
        )
    }

    /**
     * Creates the appropriate connection type based on protocol.
     */
    private fun createConnection(config: McpServerConfiguration): McpServerConnection {
        return when (config.protocol) {
            McpProtocol.STDIO -> StdioMcpConnection(
                config = config,
                processHandler = StdioProcessHandler(),
                logger = logger,
            )

            McpProtocol.HTTP, McpProtocol.SSE -> HttpMcpConnection(
                config = config,
                httpHandler = HttpClientHandler(),
                logger = logger,
            )
        }
    }

    /**
     * Checks if a specific MCP server is connected.
     *
     * @param serverId The server ID
     * @return True if connected, false otherwise
     */
    override suspend fun isConnected(serverId: McpServerId): Boolean = mutex.withLock {
        activeConnections[serverId]?.isConnected == true
    }

    /**
     * Gets the connection for a specific MCP server.
     *
     * This is used by the executor layer to invoke tools.
     *
     * @param serverId The server ID
     * @return The connection, or null if not connected
     */
    override suspend fun getConnection(serverId: McpServerId): McpServerConnection? = mutex.withLock {
        activeConnections[serverId]
    }

    /**
     * Disconnects from all MCP servers and cleans up resources.
     *
     * This should be called during application shutdown.
     *
     * @return Result indicating success or failure
     */
    suspend fun disconnectAll(): Result<Unit> = mutex.withLock {
        logger.i { "Disconnecting from all MCP servers..." }

        var errors = 0

        activeConnections.forEach { (serverId, connection) ->
            try {
                connection.disconnect().onFailure { error ->
                    logger.w(error) { "Error disconnecting from $serverId" }
                    errors++
                }

                // Unregister tools from this server
                toolRegistry.unregisterMcpServerTools(
                    serverId = serverId,
                    reason = "Server disconnected during shutdown",
                )
            } catch (e: Exception) {
                logger.w(e) { "Exception while disconnecting from $serverId" }
                errors++
            }
        }

        activeConnections.clear()

        if (errors > 0) {
            logger.w { "Disconnected from all servers with $errors errors" }
            Result.failure(Exception("Disconnection completed with $errors errors"))
        } else {
            logger.i { "Successfully disconnected from all servers" }
            Result.success(Unit)
        }
    }

    /**
     * Emits a ToolDiscoveryComplete event for a specific server.
     */
    private suspend fun emitServerDiscoveryComplete(
        serverId: McpServerId,
        serverName: String,
        toolCount: Int,
    ) {
        // Note: We emit the existing ToolEvent.ToolDiscoveryComplete with server-specific info
        // The event is already designed to support this use case
        val event = ToolEvent.ToolDiscoveryComplete(
            eventId = generateUUID(serverId, "discovery"),
            timestamp = Clock.System.now(),
            eventSource = eventSource,
            urgency = Urgency.LOW,
            totalToolsDiscovered = toolCount,
            functionToolCount = 0, // Only MCP tools in this event
            mcpToolCount = toolCount,
            mcpServerCount = 1, // Single server in this event
        )

        eventBus.publish(event)
    }
}

/**
 * Result of MCP server discovery process.
 */
data class McpDiscoveryResult(
    val totalServers: Int,
    val successfulServers: Int,
    val failedServers: Int,
    val totalToolsDiscovered: Int,
    val serverResults: Map<McpServerId, ServerDiscoveryResult>,
) {
    /** Whether all servers were successfully discovered */
    val isFullSuccess: Boolean
        get() = failedServers == 0

    /** Whether at least some servers were discovered */
    val isPartialSuccess: Boolean
        get() = successfulServers > 0
}

/**
 * Result of discovering tools from a single server.
 */
data class ServerDiscoveryResult(
    val serverId: McpServerId,
    val serverName: String,
    val toolsDiscovered: Int,
    val success: Boolean,
    val error: String?,
)

/**
 * Exception thrown during MCP discovery process.
 */
class McpDiscoveryException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
