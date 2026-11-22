package link.socket.ampere.agents.tools.mcp

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.config.AgentActionAutonomy
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
 * - HTTP: Remote HTTP/HTTPS endpoints over the Streamable HTTP transport
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
     * - For HTTP: the URL of the MCP endpoint (e.g., "https://api.example.com/mcp")
     */
    val endpoint: String,

    /**
     * Optional authentication token.
     * For HTTP, this is sent as a Bearer token in the Authorization header.
     */
    val authToken: String? = null,

    /**
     * The minimum autonomy level required to use tools from this server.
     * Tools discovered from this server will inherit this autonomy requirement.
     */
    val requiredAgentAutonomy: AgentActionAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,

    /**
     * Optional timeout in milliseconds for each request, the handshake included:
     * how long the server has to finish answering it. Defaults to 30 seconds.
     */
    val timeoutMs: Long = 30_000,

    /**
     * Whether to automatically reconnect if the connection is lost.
     * Not read by either connection today. An HTTP connection replaces a session
     * the server ended whatever this says, since the transport requires it.
     */
    val autoReconnect: Boolean = false,
)

/**
 * MCP transport protocol types.
 *
 * ### There is no `SSE` member (AMPR-514)
 *
 * There used to be one, documented as "Server-Sent Events for streaming
 * connections". It never had an implementation: it built the same connection
 * as [HTTP], so it promised a transport that did not exist. It was removed
 * rather than implemented, because the transport its name suggests is the
 * HTTP+SSE transport of the `2024-11-05` revision (a long-lived `GET` stream
 * plus a separate `POST` endpoint the stream announces), which the
 * specification replaced in `2025-03-26` and has since deprecated, and which
 * no server this client is pointed at speaks.
 *
 * Migration: use [HTTP]. It is the Streamable HTTP transport, which reads a
 * server-sent event stream whenever a server answers a request with one, so a
 * configuration that said `SSE` and worked was already getting [HTTP]'s
 * behaviour and keeps it. A server that speaks only the old HTTP+SSE transport
 * was never reachable through `SSE` either; it answers [HTTP]'s first `POST`
 * with a 4xx, which the handshake reports.
 */
@Serializable
enum class McpProtocol {
    /**
     * Standard Input/Output - for local executable processes.
     * The MCP server runs as a child process and communicates via stdin/stdout.
     */
    STDIO,

    /**
     * Streamable HTTP - for remote endpoints.
     * Every JSON-RPC message is an HTTP POST to the one MCP endpoint; the server
     * answers each request with a JSON body or with a server-sent event stream.
     * See [link.socket.ampere.agents.tools.mcp.connection.HttpMcpConnection].
     */
    HTTP,
}
