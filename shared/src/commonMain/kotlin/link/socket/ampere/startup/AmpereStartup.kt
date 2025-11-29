package link.socket.ampere.startup

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.tools.ToolInitializationResult
import link.socket.ampere.agents.tools.initializeLocalTools
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
 * 3. Emits discovery complete events
 * 4. Returns the registry for use by agents
 *
 * @param database The SQLDelight database for persistence
 * @param json The JSON serializer for data operations
 * @param scope The coroutine scope for async operations
 * @param logger Optional logger for observability
 * @return Result containing the initialized ToolRegistry and initialization stats
 */
suspend fun initializeAmpere(
    database: Database,
    json: Json,
    scope: CoroutineScope,
    logger: Logger = Logger.withTag("AmpereStartup")
): Result<AmpereStartupResult> {
    logger.i { "=== Ampere System Initialization Starting ===" }

    return try {
        // Create the tool registry
        logger.i { "Creating tool registry..." }
        val registry = createToolRegistry(database, json, scope)

        // Initialize local function tools
        logger.i { "Initializing local function tools..." }
        val toolInitResult = initializeLocalTools(registry, logger).getOrThrow()

        if (!toolInitResult.isFullSuccess) {
            logger.w {
                "Tool initialization completed with ${toolInitResult.failedRegistrations} failures. " +
                "See logs above for details."
            }
        }

        // Emit discovery complete event
        logger.i { "Emitting tool discovery complete event..." }
        registry.emitDiscoveryComplete(mcpServerCount = 0)

        logger.i {
            "=== Ampere System Initialization Complete ===" +
            "\n  Total tools: ${toolInitResult.successfulRegistrations}" +
            "\n  Failed registrations: ${toolInitResult.failedRegistrations}" +
            "\n  Status: ${if (toolInitResult.isFullSuccess) "SUCCESS" else "PARTIAL SUCCESS"}"
        }

        Result.success(
            AmpereStartupResult(
                registry = registry,
                toolInitialization = toolInitResult
            )
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
 * @return Configured ToolRegistry instance
 */
private fun createToolRegistry(
    database: Database,
    json: Json,
    scope: CoroutineScope
): ToolRegistry {
    val repository = ToolRegistryRepository(
        json = json,
        scope = scope,
        database = database
    )

    val eventBus = EventSerialBus(scope = scope)

    val eventSource = EventSource.Agent(agentId = "ampere-system")

    return ToolRegistry(
        repository = repository,
        eventBus = eventBus,
        eventSource = eventSource
    )
}

/**
 * Result of Ampere system initialization.
 *
 * @property registry The initialized ToolRegistry
 * @property toolInitialization Statistics from tool initialization
 */
data class AmpereStartupResult(
    val registry: ToolRegistry,
    val toolInitialization: ToolInitializationResult
) {
    /** Whether initialization was fully successful */
    val isFullSuccess: Boolean
        get() = toolInitialization.isFullSuccess

    /** Whether at least partial initialization succeeded */
    val isPartialSuccess: Boolean
        get() = toolInitialization.isPartialSuccess
}
