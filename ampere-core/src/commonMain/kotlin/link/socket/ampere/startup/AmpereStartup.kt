package link.socket.ampere.startup

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.tools.initializeLocalTools
import link.socket.ampere.agents.tools.mcp.McpDiscoveryResult
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration
import link.socket.ampere.agents.tools.mcp.McpServerManager
import link.socket.ampere.agents.tools.registry.ToolRegistry
import link.socket.ampere.agents.tools.registry.ToolRegistryRepository
import link.socket.ampere.db.Database

/**
 * AmpereStartup - Central initialization point for the Ampere system.
 *
 * This handles all startup tasks that need to happen before the application
 * is ready to serve agents, including:
 * - Tool registry initialization
 * - Local function tool registration
 * - MCP server discovery (future tasks)
 * - Event system setup
 *
 * Call initializeAmpere() at application startup, before any agents are created.
 */

/**
 * Initializes the Ampere system with all required components.
 *
 * This is the main entry point for system initialization. It should be called
 * early in application startup (e.g., in the main composable or application onCreate).
 *
 * The function:
 * 1. Creates the ToolRegistry with persistence
 * 2. Initializes local function tools
 * 3. Discovers and registers tools from MCP servers (if configured)
 * 4. Emits discovery complete events
 * 5. Returns the registry and MCP manager for use by agents
 *
 * @param database The SQLDelight database for persistence
 * @param json The JSON serializer for data operations
 * @param scope The coroutine scope for async operations
 * @param mcpServerConfigs Optional list of MCP server configurations to discover
 * @param logger Optional logger for observability
 * @return Result containing the initialized ToolRegistry, ServerManager, and initialization stats
 */
suspend fun initializeAmpere(
    database: Database,
    json: Json,
    scope: CoroutineScope,
    mcpServerConfigs: List<McpServerConfiguration> = emptyList(),
    logger: Logger = Logger.withTag("AmpereStartup"),
): Result<AmpereStartupResult> {
    logger.i { "=== Ampere System Initialization Starting ===" }

    return try {
        // Create the tool registry
        logger.i { "Creating tool registry..." }
        val (registry, eventBus, eventSource) = createToolRegistry(database, json, scope)

        // Initialize local function tools
        logger.i { "Initializing local function tools..." }
        val toolInitResult = initializeLocalTools(registry, logger).getOrThrow()

        if (!toolInitResult.isFullSuccess) {
            logger.w {
                "Tool initialization completed with ${toolInitResult.failedRegistrations} failures. " +
                    "See logs above for details."
            }
        }

        // Create MCP server manager
        logger.i { "Creating MCP server manager..." }
        val mcpManager = McpServerManager(
            toolRegistry = registry,
            eventBus = eventBus,
            eventSource = eventSource,
            logger = logger,
        )

        // Discover and register MCP tools if servers are configured
        var mcpDiscoveryResult: McpDiscoveryResult? = null
        if (mcpServerConfigs.isNotEmpty()) {
            logger.i { "Discovering tools from ${mcpServerConfigs.size} MCP servers..." }

            // Add all server configurations
            mcpServerConfigs.forEach { config ->
                mcpManager.addServerConfiguration(config)
            }

            // Perform discovery
            mcpDiscoveryResult = mcpManager.discoverAndRegisterTools().getOrElse { error ->
                logger.w(error) { "MCP discovery failed, continuing without MCP tools" }
                null
            }

            if (mcpDiscoveryResult != null) {
                if (!mcpDiscoveryResult.isFullSuccess) {
                    logger.w {
                        "MCP discovery completed with ${mcpDiscoveryResult.failedServers} failures. " +
                            "See logs above for details."
                    }
                }
            }
        } else {
            logger.i { "No MCP servers configured, skipping MCP discovery" }
        }

        // Emit final discovery complete event
        logger.i { "Emitting final tool discovery complete event..." }
        registry.emitDiscoveryComplete(
            mcpServerCount = mcpDiscoveryResult?.successfulServers ?: 0,
        )

        val totalTools = toolInitResult.successfulRegistrations +
            (mcpDiscoveryResult?.totalToolsDiscovered ?: 0)

        logger.i {
            val status = if (toolInitResult.isFullSuccess && (mcpDiscoveryResult?.isFullSuccess != false)) {
                "SUCCESS"
            } else {
                "PARTIAL SUCCESS"
            }
            "=== Ampere System Initialization Complete ===" +
                "\n  Local tools: ${toolInitResult.successfulRegistrations}" +
                "\n  MCP tools: ${mcpDiscoveryResult?.totalToolsDiscovered ?: 0}" +
                "\n  Total tools: $totalTools" +
                "\n  MCP servers: ${mcpDiscoveryResult?.successfulServers ?: 0}/${mcpServerConfigs.size}" +
                "\n  Status: $status"
        }

        Result.success(
            AmpereStartupResult(
                registry = registry,
                serverManager = mcpManager,
                toolInitialization = toolInitResult,
                mcpDiscovery = mcpDiscoveryResult,
            ),
        )
    } catch (e: Exception) {
        logger.e(e) { "Failed to initialize Ampere system" }
        Result.failure(e)
    }
}

/**
 * Creates and configures the ToolRegistry.
 *
 * @param database The SQLDelight database for persistence
 * @param json The JSON serializer
 * @param scope The coroutine scope
 * @return Triple of (ToolRegistry, EventSerialBus, EventSource)
 */
private fun createToolRegistry(
    database: Database,
    json: Json,
    scope: CoroutineScope,
): Triple<ToolRegistry, EventSerialBus, EventSource> {
    val repository = ToolRegistryRepository(
        json = json,
        scope = scope,
        database = database,
    )

    val eventBus = EventSerialBus(scope = scope)

    val eventSource = EventSource.Agent(agentId = "ampere-system")

    val registry = ToolRegistry(
        repository = repository,
        eventBus = eventBus,
        eventSource = eventSource,
    )

    return Triple(registry, eventBus, eventSource)
}
