package link.socket.ampere.startup

import link.socket.ampere.agents.tools.ToolInitializationResult
import link.socket.ampere.agents.tools.mcp.McpDiscoveryResult
import link.socket.ampere.agents.tools.mcp.ServerManager
import link.socket.ampere.agents.tools.registry.ToolRegistry

/**
 * Result of Ampere system initialization.
 *
 * @property registry The initialized ToolRegistry
 * @property serverManager The server manager for external tool integration
 * @property toolInitialization Statistics from local tool initialization
 * @property mcpDiscovery Statistics from MCP tool discovery (null if no MCP servers configured)
 */
data class AmpereStartupResult(
    val registry: ToolRegistry,
    val serverManager: ServerManager,
    val toolInitialization: ToolInitializationResult,
    val mcpDiscovery: McpDiscoveryResult? = null,
) {
    /** Whether initialization was fully successful */
    val isFullSuccess: Boolean
        get() = toolInitialization.isFullSuccess &&
            (mcpDiscovery?.isFullSuccess != false)

    /** Whether at least partial initialization succeeded */
    val isPartialSuccess: Boolean
        get() = toolInitialization.isPartialSuccess ||
            (mcpDiscovery?.isPartialSuccess == true)

    /** Total number of tools discovered (local + MCP) */
    val totalToolsDiscovered: Int
        get() = toolInitialization.successfulRegistrations +
            (mcpDiscovery?.totalToolsDiscovered ?: 0)

    /** Number of successfully connected MCP servers */
    val mcpServersConnected: Int
        get() = mcpDiscovery?.successfulServers ?: 0
}
