package link.socket.ampere.agents.tools.mcp

import link.socket.ampere.agents.execution.tools.McpServerId
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection

/**
 * ServerManager - Interface for managing MCP server connections.
 *
 * This interface defines the contract for accessing MCP server connections,
 * allowing for dependency injection and testing.
 *
 * Implementations are responsible for:
 * - Managing server connection lifecycle
 * - Providing access to active connections
 * - Tracking connection state
 */
interface ServerManager {
    /**
     * Gets the connection for a specific MCP server.
     *
     * This is used by the executor layer to invoke tools.
     *
     * @param serverId The server ID
     * @return The connection, or null if not connected
     */
    suspend fun getConnection(serverId: McpServerId): McpServerConnection?

    /**
     * Checks if a specific MCP server is connected.
     *
     * @param serverId The server ID
     * @return True if connected, false otherwise
     */
    suspend fun isConnected(serverId: McpServerId): Boolean
}
