package link.socket.ampere.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.McpProtocol
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration
import link.socket.ampere.agents.tools.mcp.connection.HttpClientHandler
import link.socket.ampere.agents.tools.mcp.connection.HttpMcpConnection
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.connection.McpUnauthorizedException
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.McpServerDependency

/**
 * Plug-facing facade over an MCP server connection.
 *
 * Wraps an [McpServerConnection] (default [HttpMcpConnection]) with the
 * dependency declaration plus credential resolution, so plug call sites
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
 *
 * ### Credential refresh (AMPR-464)
 *
 * The credential is resolved at [connect] and baked into the connection the
 * factory builds, so a fresh token only reaches the wire through a new
 * connection. With a [credentialRefresher]:
 *
 * - [connect] refreshes first when the stored token is expired or close to it.
 * - A 401 ([McpUnauthorizedException]) from the handshake, [listTools] or
 *   [callTool] refreshes once, drops the connection that carried the refused
 *   token, opens a new one on the refreshed credential, and retries once. A
 *   second 401 is [McpCredentialException.Rejected]. A 401 is the server
 *   refusing the request before acting on it, so a retried tool call cannot
 *   land twice.
 *
 * Without one, a 401 is reported as [McpCredentialException.Rejected] at once.
 */
class McpClient(
    private val dependency: McpServerDependency,
    private val credentialBinding: McpCredentialBinding,
    private val linkId: LinkId,
    private val connectionFactory: (McpServerDependency, McpCredential?) -> McpServerConnection =
        ::defaultHttpConnection,
    private val credentialRefresher: McpCredentialRefresher? = null,
) {
    private val mutex = Mutex()
    private var connection: McpServerConnection? = null

    /** The credential [connection] was built with: what a refresh replaces after a 401. */
    private var connectionCredential: McpCredential? = null

    val isConnected: Boolean
        get() = connection?.isConnected == true

    /**
     * This client as an [McpServerConnection], or null before [connect] and after [close].
     *
     * Exposed so the [ServerManager][link.socket.ampere.agents.tools.mcp.ServerManager] a
     * [PlugContext][link.socket.ampere.plug.PlugContext] hands to
     * [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine] can
     * resolve a plug's MCP connection by server id without reopening the transport
     * (AMPR-401). `internal` because the connection is transport detail no consumer
     * needs: [callTool] is the call this class publishes, and the engine reaches the
     * server through the manager rather than holding a connection of its own.
     *
     * A view rather than the raw connection, so a tool the engine dispatches gets the
     * same refresh-and-retry on a 401 as [callTool], and keeps working across the
     * reconnect a refresh causes.
     */
    internal val activeConnection: McpServerConnection?
        get() = if (connection != null) connectionView else null

    private val connectionView: McpServerConnection = object : McpServerConnection {
        override val serverId: String
            get() = connection?.serverId ?: dependency.name

        override val isConnected: Boolean
            get() = this@McpClient.isConnected

        override suspend fun connect(): Result<Unit> = this@McpClient.connect()

        override suspend fun initialize(): Result<InitializeResult> =
            connection?.initialize() ?: Result.failure(notConnected("initialize()"))

        override suspend fun listTools(): Result<List<McpToolDescriptor>> = this@McpClient.listTools()

        override suspend fun invokeTool(toolName: String, arguments: JsonElement?): Result<ToolCallResult> =
            callTool(toolName, arguments)

        override suspend fun disconnect(): Result<Unit> = close()
    }

    suspend fun connect(): Result<Unit> = mutex.withLock {
        val existing = connection
        if (existing != null && existing.isConnected) {
            return Result.success(Unit)
        }

        var credential = credentialBinding.resolve(linkId, dependency.uri)
            .getOrElse { return Result.failure(it) }

        if (credentialRefresher != null && credential != null && credentialRefresher.isDue(credential)) {
            credential = credentialRefresher.refresh(linkId, dependency.uri, stale = credential)
                .getOrElse { return Result.failure(it) }
        }

        val opened = open(credential)
        val refused = opened.exceptionOrNull() as? McpUnauthorizedException ?: return opened.map { }

        // The handshake was refused: the token was revoked, or spent by another holder, before
        // its stated expiry. Refresh once and open once more.
        reopenAfter(refused).map { }
    }

    suspend fun listTools(): Result<List<McpToolDescriptor>> =
        withCredentialRetry("listTools()") { it.listTools() }

    suspend fun callTool(name: String, arguments: JsonElement?): Result<ToolCallResult> =
        withCredentialRetry("callTool()") { it.invokeTool(name, arguments) }

    suspend fun close(): Result<Unit> = mutex.withLock {
        val active = connection ?: return Result.success(Unit)
        return active.disconnect().also {
            connection = null
            connectionCredential = null
        }
    }

    /**
     * Run [operation] on the live connection; on a 401, refresh once, reconnect, and
     * run it once more on the new connection.
     */
    private suspend fun <T> withCredentialRetry(
        call: String,
        operation: suspend (McpServerConnection) -> Result<T>,
    ): Result<T> {
        val active = connection ?: return Result.failure(notConnected(call))

        val first = operation(active)
        val refused = first.exceptionOrNull() as? McpUnauthorizedException ?: return first

        val reopened = mutex.withLock {
            // Another caller's 401 may already have reopened the connection; use theirs.
            val current = connection
            if (current != null && current !== active) {
                Result.success(current)
            } else {
                reopenAfter(refused)
            }
        }.getOrElse { return Result.failure(it) }

        val second = operation(reopened)
        val refusedAgain = second.exceptionOrNull() as? McpUnauthorizedException ?: return second
        return Result.failure(McpCredentialException.Rejected(linkId, refreshed = true, cause = refusedAgain))
    }

    /**
     * After [refused]: refresh the credential the current connection carries, drop that
     * connection — it holds the refused token — and open a new one on the refreshed
     * credential. Must be called holding [mutex].
     */
    private suspend fun reopenAfter(refused: McpUnauthorizedException): Result<McpServerConnection> {
        val refresher = credentialRefresher
            ?: return Result.failure(McpCredentialException.Rejected(linkId, refreshed = false, cause = refused))

        val refreshed = refresher.refresh(linkId, dependency.uri, stale = connectionCredential)
            .getOrElse { return Result.failure(it) }

        connection?.disconnect()
        connection = null
        connectionCredential = null

        val reopened = open(refreshed)
        val refusedAgain = reopened.exceptionOrNull() as? McpUnauthorizedException ?: return reopened
        return Result.failure(McpCredentialException.Rejected(linkId, refreshed = true, cause = refusedAgain))
    }

    /** Build a connection on [credential], cache it, and run the transport connect and the handshake. */
    private suspend fun open(credential: McpCredential?): Result<McpServerConnection> {
        val newConnection = connectionFactory(dependency, credential)
        connection = newConnection
        connectionCredential = credential

        return newConnection.connect()
            .mapCatching {
                newConnection.initialize().getOrThrow()
                newConnection
            }
    }

    private fun notConnected(call: String) = IllegalStateException("McpClient must connect() before $call")
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
