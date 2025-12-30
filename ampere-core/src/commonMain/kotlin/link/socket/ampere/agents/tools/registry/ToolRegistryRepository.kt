package link.socket.ampere.agents.tools.registry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.tools.McpServerId
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.data.Repository
import link.socket.ampere.db.Database
import link.socket.ampere.db.tools.ToolRegistryQueries

/**
 * Repository responsible for persisting and querying tool metadata using SQLDelight.
 *
 * This repository stores metadata about all tools (FunctionTool and McpTool) that have been
 * discovered and registered in the system. It enables:
 * - Recovery after system restarts (tools can be reloaded from database)
 * - Observability (track when tools were registered, last seen, etc.)
 * - Agent self-improvement (analyze past tool usage patterns)
 *
 * Note: This repository stores METADATA only. The actual execution functions for FunctionTools
 * are not serializable and must be registered separately at runtime.
 */
class ToolRegistryRepository(
    override val json: Json,
    override val scope: CoroutineScope,
    private val database: Database,
) : Repository<ToolId, ToolMetadata>(json, scope) {

    override val tag: String = "ToolRegistry${super.tag}"

    private val queries: ToolRegistryQueries
        get() = database.toolRegistryQueries

    /**
     * Persist tool metadata to the database.
     * If a tool with the same ID already exists, it will be replaced.
     */
    suspend fun saveTool(metadata: ToolMetadata): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.insertTool(
                    id = metadata.id,
                    name = metadata.name,
                    description = metadata.description,
                    tool_type = metadata.toolType,
                    required_agent_autonomy = metadata.requiredAgentAutonomy.name,
                    mcp_server_id = metadata.mcpServerId,
                    remote_tool_name = metadata.remoteToolName,
                    input_schema = metadata.inputSchema,
                    registered_at = metadata.registeredAt.toEpochMilliseconds(),
                    last_seen_at = metadata.lastSeenAt.toEpochMilliseconds(),
                )
            }.map { }
        }

    /**
     * Retrieve tool metadata by ID.
     */
    suspend fun getToolById(id: ToolId): Result<ToolMetadata?> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .getToolById(id)
                    .executeAsOneOrNull()
            }.map { row ->
                row?.let { rowToMetadata(it) }
            }
        }

    /**
     * Retrieve all registered tools.
     */
    suspend fun getAllTools(): Result<List<ToolMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .getAllTools()
                    .executeAsList()
            }.map { rows ->
                rows.map { row -> rowToMetadata(row) }
            }
        }

    /**
     * Retrieve tools by type ("function" or "mcp").
     */
    suspend fun getToolsByType(toolType: String): Result<List<ToolMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .getToolsByType(toolType)
                    .executeAsList()
            }.map { rows ->
                rows.map { row -> rowToMetadata(row) }
            }
        }

    /**
     * Retrieve tools by autonomy level.
     * Note: This returns tools with the exact autonomy level specified.
     * Filtering by "level and below" is handled in the ToolRegistry service layer.
     */
    suspend fun getToolsByAutonomy(autonomy: AgentActionAutonomy): Result<List<ToolMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .getToolsByAutonomy(autonomy.name)
                    .executeAsList()
            }.map { rows ->
                rows.map { row -> rowToMetadata(row) }
            }
        }

    /**
     * Retrieve all tools for a specific MCP server.
     */
    suspend fun getToolsByMcpServer(serverId: McpServerId): Result<List<ToolMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .getToolsByMcpServer(serverId)
                    .executeAsList()
            }.map { rows ->
                rows.map { row -> rowToMetadata(row) }
            }
        }

    /**
     * Search tools by name or description (case-insensitive partial match).
     */
    suspend fun searchTools(searchTerm: String): Result<List<ToolMetadata>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .searchTools(searchTerm, searchTerm)
                    .executeAsList()
            }.map { rows ->
                rows.map { row -> rowToMetadata(row) }
            }
        }

    /**
     * Delete a tool from the registry.
     */
    suspend fun deleteTool(id: ToolId): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.deleteTool(id)
            }.map { }
        }

    /**
     * Delete all tools for a specific MCP server (e.g., when server disconnects).
     */
    suspend fun deleteToolsByMcpServer(serverId: McpServerId): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.deleteToolsByMcpServer(serverId)
            }.map { }
        }

    /**
     * Update the last_seen_at timestamp for a tool.
     */
    suspend fun updateLastSeen(id: ToolId, timestamp: Instant): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.updateLastSeen(
                    last_seen_at = timestamp.toEpochMilliseconds(),
                    id = id,
                )
            }.map { }
        }

    /**
     * Clear all tools from the registry (useful for testing).
     */
    suspend fun clearAllTools(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries.clearAllTools()
            }.map { }
        }

    /**
     * Get count of tools by type.
     */
    suspend fun countToolsByType(): Result<Map<String, Long>> =
        withContext(Dispatchers.IO) {
            runCatching {
                queries
                    .countToolsByType()
                    .executeAsList()
            }.map { rows ->
                rows.associate { it.tool_type to it.COUNT }
            }
        }

    /**
     * Convert a database row to ToolMetadata.
     */
    private fun rowToMetadata(row: link.socket.ampere.db.tools.ToolRegistry): ToolMetadata {
        return ToolMetadata(
            id = row.id,
            name = row.name,
            description = row.description,
            toolType = row.tool_type,
            requiredAgentAutonomy = AgentActionAutonomy.valueOf(row.required_agent_autonomy),
            mcpServerId = row.mcp_server_id,
            remoteToolName = row.remote_tool_name,
            inputSchema = row.input_schema,
            registeredAt = Instant.fromEpochMilliseconds(row.registered_at),
            lastSeenAt = Instant.fromEpochMilliseconds(row.last_seen_at),
        )
    }
}
