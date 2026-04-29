package link.socket.ampere.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.McpProtocol
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration
import link.socket.ampere.agents.tools.mcp.connection.HttpClientHandler
import link.socket.ampere.agents.tools.mcp.connection.HttpMcpConnection
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.plugin.McpServerDependency

/**
 * Plugin-facing facade over an MCP server connection.
 *
 * Wraps an [McpServerConnection] (default [HttpMcpConnection]) with the
 * dependency declaration plus credential resolution, so plugin call sites
 * stay free of transport details. JSON-RPC framing remains in
 * [link.socket.ampere.agents.tools.mcp.protocol.McpClient]; this class
 * doesn't reimplement it.
 *
 * The [connectionFactory] seam exists so tests can substitute mock
 * connections without touching the production HTTP path.
 *
 * Lifecycle:
 * 1. [connect] — resolve credential, build the connection, run handshake.
 * 2. [listTools] — query tool descriptors from the server.
 * 3. [callTool] — invoke a remote tool.
 * 4. [close] — disconnect and release transport resources.
 */
class McpClient(
    private val dependency: McpServerDependency,
    private val credentialBinding: McpCredentialBinding,
    private val linkId: LinkId,
    private val connectionFactory: (McpServerDependency, McpCredential?) -> McpServerConnection =
        ::defaultHttpConnection,
) {
    private val mutex = Mutex()
    private var connection: McpServerConnection? = null

    val isConnected: Boolean
        get() = connection?.isConnected == true

    suspend fun connect(): Result<Unit> = mutex.withLock {
        val existing = connection
        if (existing != null && existing.isConnected) {
            return Result.success(Unit)
        }

        val credentialResult = credentialBinding.resolve(linkId, dependency.uri)
        val credential = credentialResult.getOrElse { return Result.failure(it) }

        val newConnection = connectionFactory(dependency, credential)
        connection = newConnection

        return newConnection.connect()
            .mapCatching {
                newConnection.initialize().getOrThrow()
                Unit
            }
    }

    suspend fun listTools(): Result<List<McpToolDescriptor>> {
        val active = connection
            ?: return Result.failure(IllegalStateException("McpClient must connect() before listTools()"))
        return active.listTools()
    }

    suspend fun callTool(name: String, arguments: JsonElement?): Result<ToolCallResult> {
        val active = connection
            ?: return Result.failure(IllegalStateException("McpClient must connect() before callTool()"))
        return active.invokeTool(name, arguments)
    }

    suspend fun close(): Result<Unit> = mutex.withLock {
        val active = connection ?: return Result.success(Unit)
        return active.disconnect().also { connection = null }
    }
}

/**
 * Default factory: builds an [HttpMcpConnection] from a dependency plus the
 * resolved credential. Used unless the caller injects a different factory
 * (typically for tests).
 */
fun defaultHttpConnection(
    dependency: McpServerDependency,
    credential: McpCredential?,
): McpServerConnection {
    val config = McpServerConfiguration(
        id = dependency.name,
        displayName = dependency.name,
        protocol = McpProtocol.HTTP,
        endpoint = dependency.uri,
        authToken = credential?.authToken,
    )
    return HttpMcpConnection(config, HttpClientHandler())
}
