package link.socket.ampere.mcp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.connection.McpUnauthorizedException
import link.socket.ampere.agents.tools.mcp.protocol.ContentItem
import link.socket.ampere.agents.tools.mcp.protocol.InitializeResult
import link.socket.ampere.agents.tools.mcp.protocol.McpToolDescriptor
import link.socket.ampere.agents.tools.mcp.protocol.ServerCapabilities
import link.socket.ampere.agents.tools.mcp.protocol.ServerInfo
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.McpServerDependency

/** Obviously fake OAuth client details. Nothing here is, or resembles, a real credential. */
internal val fakeClient = McpOAuthClient(
    tokenEndpoint = "https://issuer.invalid/oauth/token",
    clientId = "fake-client-id",
    clientSecret = "fake-client-secret",
)

/**
 * A [SecretStore] over a map that outlives any binding built on it — the
 * keychain, in effect, which survives the process. Records every write and
 * delete, and can be told to refuse writes.
 */
internal class InMemorySecretStore : SecretStore {

    val items = mutableMapOf<Pair<String, String>, String>()
    val writes = mutableListOf<Pair<Pair<String, String>, String>>()
    val deletes = mutableListOf<Pair<String, String>>()

    /** How many upcoming writes to refuse, as a locked keychain would. */
    var refuseWrites: Int = 0

    override suspend fun read(service: String, account: String): Result<String?> =
        Result.success(items[service to account])

    override suspend fun write(service: String, account: String, secret: String): Result<Unit> {
        if (refuseWrites > 0) {
            refuseWrites -= 1
            return Result.failure(IllegalStateException("the keychain is locked"))
        }
        items[service to account] = secret
        writes += (service to account) to secret
        return Result.success(Unit)
    }

    override suspend fun delete(service: String, account: String): Result<Unit> {
        items.remove(service to account)
        deletes += service to account
        return Result.success(Unit)
    }
}

/**
 * A token issuer that rotates its refresh token on every refresh and, like the
 * work source, honours a spent one for a [grace] window by answering it again
 * with what it answered the first time.
 */
internal class RotatingIssuer(
    initialRefreshToken: String,
    private val clock: Clock,
    private val grace: Duration = 30.minutes,
    private val expiresIn: Long = 86_399,
) : OAuthTokenEndpoint {

    /** The refresh token the issuer honours right now. */
    var current: String = initialRefreshToken
        private set

    /** Every refresh token presented, in order. */
    val presented = mutableListOf<String>()

    /** When set, every refresh answers this instead. */
    var outage: OAuthTokenResult.Unavailable? = null

    private val spent = mutableMapOf<String, Pair<Instant, OAuthTokenResult.Issued>>()
    private var issued = 0

    override suspend fun refresh(client: McpOAuthClient, refreshToken: String): OAuthTokenResult {
        presented += refreshToken
        outage?.let { return it }

        if (refreshToken == current) {
            issued += 1
            val answer = OAuthTokenResult.Issued(
                accessToken = "fake-access-$issued",
                refreshToken = "fake-refresh-$issued",
                expiresIn = expiresIn,
            )
            spent[refreshToken] = clock.now() to answer
            current = answer.refreshToken!!
            return answer
        }

        val (spentAt, answer) = spent[refreshToken] ?: return OAuthTokenResult.Rejected("invalid_grant")
        return if (clock.now() - spentAt <= grace) answer else OAuthTokenResult.Rejected("invalid_grant")
    }
}

/** A token endpoint that answers whatever the test scripts, recording what it was handed. */
internal class ScriptedTokenEndpoint(
    private val answer: suspend (refreshToken: String) -> OAuthTokenResult,
) : OAuthTokenEndpoint {

    val presented = mutableListOf<String>()

    override suspend fun refresh(client: McpOAuthClient, refreshToken: String): OAuthTokenResult {
        presented += refreshToken
        return answer(refreshToken)
    }
}

/**
 * A binding that stands in for a process killed around the write of a refreshed
 * credential: it throws [CancellationException], which nothing on the refresh path
 * catches, so the call dies the way a killed process would.
 *
 * @param writeFirst Whether the write lands before the kill.
 */
internal class KilledAroundWriteBinding(
    private val delegate: McpCredentialBinding,
    private val writeFirst: Boolean,
) : McpCredentialBinding by delegate {

    override suspend fun bind(linkId: LinkId, mcpUri: String, credential: McpCredential): Result<Unit> {
        if (writeFirst) delegate.bind(linkId, mcpUri, credential).getOrThrow()
        throw CancellationException("process killed")
    }
}

/**
 * An MCP server that accepts a request only when it carries a token in [accepted].
 * A test revokes a token mid-session by removing it.
 */
internal class TokenCheckingServer(
    vararg acceptedTokens: String,
) {
    val accepted = acceptedTokens.toMutableSet()

    /** When false, the handshake accepts any token and only tool calls are checked. */
    var checkHandshake: Boolean = true

    /** Every connection the factory built, in order, with the token each carried. */
    val connections = mutableListOf<Connection>()

    val factory: (McpServerDependency, McpCredential?) -> McpServerConnection =
        { _, credential -> Connection(credential?.authToken).also { connections += it } }

    inner class Connection(val token: String?) : McpServerConnection {

        override val serverId: String = "token-checking"

        override var isConnected: Boolean = false
            private set

        var disconnected: Boolean = false
            private set

        val calls = mutableListOf<String>()

        override suspend fun connect(): Result<Unit> {
            if (checkHandshake && !accepts()) return refused("Endpoint validation")
            isConnected = true
            return Result.success(Unit)
        }

        override suspend fun initialize(): Result<InitializeResult> =
            Result.success(
                InitializeResult(
                    protocolVersion = "2024-11-05",
                    serverInfo = ServerInfo(name = "TokenChecking", version = "0.0.0"),
                    capabilities = ServerCapabilities(),
                ),
            )

        override suspend fun listTools(): Result<List<McpToolDescriptor>> {
            if (!accepts()) return refused("HTTP request")
            return Result.success(listOf(McpToolDescriptor(name = "get_issue", description = "Get an issue")))
        }

        override suspend fun invokeTool(toolName: String, arguments: JsonElement?): Result<ToolCallResult> {
            calls += toolName
            if (!accepts()) return refused("HTTP request")
            return Result.success(ToolCallResult(content = listOf(ContentItem(type = "text", text = "ok"))))
        }

        override suspend fun disconnect(): Result<Unit> {
            isConnected = false
            disconnected = true
            return Result.success(Unit)
        }

        private fun accepts(): Boolean = token != null && token in accepted

        private fun <T> refused(action: String): Result<T> =
            Result.failure(McpUnauthorizedException("$action failed: 401 Unauthorized"))
    }
}
