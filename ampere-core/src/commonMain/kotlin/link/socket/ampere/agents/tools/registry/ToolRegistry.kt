package link.socket.ampere.agents.tools.registry

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.tools.McpServerId
import link.socket.ampere.agents.execution.tools.McpTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolId

/**
 * ToolRegistry - The "sensory cortex" for agent capabilities.
 *
 * This service maintains an in-memory cache of all available tools (both FunctionTool and McpTool),
 * provides query methods for tool discovery, emits events when the tool landscape changes,
 * and persists tool metadata to the database for observability and recovery.
 *
 * Think of this as the system's awareness layer. Before an agent can decide to use a tool,
 * they need to know it exists. The registry makes tools discoverable, queryable, and observable.
 *
 * Key responsibilities:
 * - Register tools and store metadata in database
 * - Maintain fast in-memory cache for queries
 * - Emit events when tools are registered/unregistered
 * - Support filtering by autonomy level and capability
 * - Load persisted tools on startup
 *
 * Thread-safety: All public methods are protected by a mutex for safe concurrent access.
 */
class ToolRegistry(
    private val repository: ToolRegistryRepository,
    private val eventBus: EventSerialBus,
    private val eventSource: EventSource,
) {
    // In-memory cache for fast lookups
    // Maps ToolId -> ToolMetadata
    private val toolCache = mutableMapOf<ToolId, ToolMetadata>()

    // Mutex for thread-safe access to the cache
    private val mutex = Mutex()

    /**
     * Register a tool in the registry.
     *
     * This:
     * 1. Creates metadata from the tool
     * 2. Stores it in the database
     * 3. Adds it to the in-memory cache
     * 4. Emits a ToolRegistered event
     *
     * If a tool with the same ID already exists, it will be replaced.
     *
     * @param tool The tool to register (FunctionTool or McpTool)
     * @param timestamp When the tool was registered (defaults to now)
     * @return Result indicating success or failure
     */
    suspend fun registerTool(
        tool: Tool<*>,
        timestamp: Instant = Clock.System.now(),
    ): Result<Unit> = mutex.withLock {
        val metadata = ToolMetadata.fromTool(tool, timestamp)

        // Persist to database
        repository.saveTool(metadata).onSuccess {
            // Add to cache
            toolCache[tool.id] = metadata

            // Emit event
            val event = ToolEvent.ToolRegistered(
                eventId = generateUUID(tool.id),
                timestamp = timestamp,
                eventSource = eventSource,
                urgency = Urgency.LOW,
                toolId = tool.id,
                toolName = tool.name,
                toolType = metadata.toolType,
                requiredAutonomy = tool.requiredAgentAutonomy,
                mcpServerId = when (tool) {
                    is McpTool -> tool.serverId
                    else -> null
                },
            )

            eventBus.publish(event)
        }
    }

    /**
     * Unregister a tool from the registry.
     *
     * This:
     * 1. Removes it from the database
     * 2. Removes it from the in-memory cache
     * 3. Emits a ToolUnregistered event
     *
     * @param toolId The ID of the tool to unregister
     * @param reason Human-readable explanation for removal
     * @return Result indicating success or failure
     */
    suspend fun unregisterTool(
        toolId: ToolId,
        reason: String,
    ): Result<Unit> = mutex.withLock {
        // Get metadata before removing
        val metadata = toolCache[toolId]

        repository.deleteTool(toolId).onSuccess {
            // Remove from cache
            toolCache.remove(toolId)

            // Emit event
            if (metadata != null) {
                val event = ToolEvent.ToolUnregistered(
                    eventId = generateUUID(toolId),
                    timestamp = Clock.System.now(),
                    eventSource = eventSource,
                    urgency = Urgency.MEDIUM,
                    toolId = toolId,
                    toolName = metadata.name,
                    reason = reason,
                    mcpServerId = metadata.mcpServerId,
                )

                eventBus.publish(event)
            }
        }
    }

    /**
     * Unregister all tools for a specific MCP server.
     *
     * This is used when an MCP server disconnects.
     *
     * @param serverId The MCP server ID
     * @param reason Human-readable explanation for removal
     * @return Result indicating success or failure
     */
    suspend fun unregisterMcpServerTools(
        serverId: McpServerId,
        reason: String,
    ): Result<Unit> = mutex.withLock {
        // Get all tools for this server before removing
        val toolsToRemove = toolCache.values.filter { it.mcpServerId == serverId }

        repository.deleteToolsByMcpServer(serverId).onSuccess {
            // Remove from cache and emit events
            toolsToRemove.forEach { metadata ->
                toolCache.remove(metadata.id)

                val event = ToolEvent.ToolUnregistered(
                    eventId = generateUUID(metadata.id, serverId),
                    timestamp = Clock.System.now(),
                    eventSource = eventSource,
                    urgency = Urgency.MEDIUM,
                    toolId = metadata.id,
                    toolName = metadata.name,
                    reason = reason,
                    mcpServerId = serverId,
                )

                eventBus.publish(event)
            }
        }
    }

    /**
     * Get a specific tool by ID.
     *
     * @param toolId The tool ID to look up
     * @return The tool metadata, or null if not found
     */
    suspend fun getTool(toolId: ToolId): ToolMetadata? = mutex.withLock {
        toolCache[toolId]
    }

    /**
     * Get all registered tools.
     *
     * @return List of all tool metadata
     */
    suspend fun getAllTools(): List<ToolMetadata> = mutex.withLock {
        toolCache.values.toList()
    }

    /**
     * Find tools by autonomy level (inclusive).
     *
     * Returns all tools that require the specified autonomy level OR LOWER.
     * This respects the autonomy hierarchy:
     * - ASK_BEFORE_ACTION (lowest)
     * - ACT_WITH_NOTIFICATION
     * - FULLY_AUTONOMOUS
     * - SELF_CORRECTING (highest)
     *
     * For example, if an agent has FULLY_AUTONOMOUS level, they can use tools
     * requiring ASK_BEFORE_ACTION, ACT_WITH_NOTIFICATION, or FULLY_AUTONOMOUS,
     * but NOT tools requiring SELF_CORRECTING.
     *
     * @param autonomyLevel The agent's autonomy level
     * @return List of tools accessible at this autonomy level or below
     */
    suspend fun findToolsByAutonomy(autonomyLevel: AgentActionAutonomy): List<ToolMetadata> =
        mutex.withLock {
            toolCache.values.filter { metadata ->
                // Tool is accessible if its required autonomy is <= agent's autonomy
                metadata.requiredAgentAutonomy.ordinal <= autonomyLevel.ordinal
            }
        }

    /**
     * Find tools by capability (name or description search).
     *
     * Performs a case-insensitive substring match on tool name and description.
     *
     * @param searchTerm The capability to search for (e.g., "github", "code", "database")
     * @return List of tools matching the search term
     */
    suspend fun findToolsByCapability(searchTerm: String): List<ToolMetadata> = mutex.withLock {
        val lowerSearch = searchTerm.lowercase()
        toolCache.values.filter { metadata ->
            metadata.name.lowercase().contains(lowerSearch) ||
                metadata.description.lowercase().contains(lowerSearch)
        }
    }

    /**
     * Find tools by type ("function" or "mcp").
     *
     * @param toolType The tool type to filter by
     * @return List of tools of the specified type
     */
    suspend fun findToolsByType(toolType: String): List<ToolMetadata> = mutex.withLock {
        toolCache.values.filter { it.toolType == toolType }
    }

    /**
     * Find all tools for a specific MCP server.
     *
     * @param serverId The MCP server ID
     * @return List of tools from that server
     */
    suspend fun findToolsByMcpServer(serverId: McpServerId): List<ToolMetadata> = mutex.withLock {
        toolCache.values.filter { it.mcpServerId == serverId }
    }

    /**
     * Load persisted tools from the database into the in-memory cache.
     *
     * This should be called at startup to rebuild the cache from previously
     * registered tools.
     *
     * Note: This only loads metadata. For FunctionTools, the actual execution
     * functions must be registered separately (see Task 3).
     *
     * @return Result indicating success or failure
     */
    suspend fun loadPersistedTools(): Result<Unit> = mutex.withLock {
        repository.getAllTools().map { tools ->
            tools.forEach { metadata ->
                toolCache[metadata.id] = metadata
            }
        }
    }

    /**
     * Clear all tools from the registry.
     *
     * This is primarily for testing. It clears both the database and the cache.
     *
     * @return Result indicating success or failure
     */
    suspend fun clear(): Result<Unit> = mutex.withLock {
        repository.clearAllTools().onSuccess {
            toolCache.clear()
        }
    }

    /**
     * Emit a ToolDiscoveryComplete event.
     *
     * This should be called after tool discovery has finished (e.g., after
     * all MCP servers have been queried and function tools have been initialized).
     *
     * @param mcpServerCount Number of MCP servers successfully connected
     */
    suspend fun emitDiscoveryComplete(mcpServerCount: Int = 0) {
        val stats = mutex.withLock {
            val functionCount = toolCache.values.count { it.isFunctionTool() }
            val mcpCount = toolCache.values.count { it.isMcpTool() }
            Triple(toolCache.size, functionCount, mcpCount)
        }

        val event = ToolEvent.ToolDiscoveryComplete(
            eventId = generateUUID("tool-discovery"),
            timestamp = Clock.System.now(),
            eventSource = eventSource,
            urgency = Urgency.LOW,
            totalToolsDiscovered = stats.first,
            functionToolCount = stats.second,
            mcpToolCount = stats.third,
            mcpServerCount = mcpServerCount,
        )

        eventBus.publish(event)
    }
}
