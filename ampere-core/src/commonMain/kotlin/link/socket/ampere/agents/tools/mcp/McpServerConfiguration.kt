package link.socket.ampere.agents.tools.mcp

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.execution.tools.McpServerId

/**
 * McpServerConfiguration - Defines how to connect to an MCP server.
 *
 * This configuration tells the system:
 * - What server to connect to (id, displayName)
 * - How to connect (protocol type, endpoint)
 * - What permissions tools from this server should have (requiredAgentAutonomy)
 * - Optional authentication details
 *
 * MCP servers can use different transport mechanisms:
 * - STDIO: Local executable processes that communicate via stdin/stdout
 * - HTTP: Remote HTTP/HTTPS endpoints with JSON-RPC
 * - SSE: Server-Sent Events for streaming connections
 *
 * Example configurations:
 * ```kotlin
 * // Local GitHub CLI MCP server
 * McpServerConfiguration(
 *     id = "github",
 *     displayName = "GitHub CLI",
 *     protocol = McpProtocol.STDIO,
 *     endpoint = "/usr/local/bin/github-mcp-server",
 *     requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION
 * )
 *
 * // Remote database MCP server
 * McpServerConfiguration(
 *     id = "database",
 *     displayName = "Database Query Service",
 *     protocol = McpProtocol.HTTP,
 *     endpoint = "https://db-mcp.example.com/api",
 *     authToken = "secret-token",
 *     requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION
 * )
 * ```
 */
@Serializable
data class McpServerConfiguration(
    /**
     * Unique identifier for this MCP server.
     * Used for namespacing tools (e.g., "github:list_prs").
     */
    val id: McpServerId,

    /**
     * Human-readable display name for this server.
     */
    val displayName: String,

    /**
     * The transport protocol to use for communication.
     */
    val protocol: McpProtocol,

    /**
     * The endpoint to connect to.
     * - For STDIO: path to the executable (e.g., "/usr/local/bin/server")
     * - For HTTP/SSE: the URL (e.g., "https://api.example.com/mcp")
     */
    val endpoint: String,

    /**
     * Optional authentication token.
     * For HTTP/SSE, this is typically sent as a Bearer token in the Authorization header.
     */
    val authToken: String? = null,

    /**
     * The minimum autonomy level required to use tools from this server.
     * Tools discovered from this server will inherit this autonomy requirement.
     */
    val requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,

    /**
     * Optional timeout in milliseconds for connection and requests.
     * Defaults to 30 seconds.
     */
    val timeoutMs: Long = 30_000,

    /**
     * Whether to automatically reconnect if the connection is lost.
     * Primarily useful for long-lived HTTP/SSE connections.
     */
    val autoReconnect: Boolean = false,
)

/**
 * MCP transport protocol types.
 */
@Serializable
enum class McpProtocol {
    /**
     * Standard Input/Output - for local executable processes.
     * The MCP server runs as a child process and communicates via stdin/stdout.
     */
    STDIO,

    /**
     * HTTP - for remote REST-like endpoints.
     * Uses JSON-RPC over HTTP POST requests.
     */
    HTTP,

    /**
     * Server-Sent Events - for streaming connections.
     * Uses SSE for server-to-client messages and HTTP POST for client-to-server.
     */
    SSE,
}
