package link.socket.ampere.mcp

import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.link.LinkId

/**
 * Credential material a plug's [McpClient] surfaces into its connection
 * layer when calling an MCP server.
 *
 * [authToken] is all a static bearer needs. An OAuth credential adds what it
 * takes to mint the next one (AMPR-464): the [refreshToken], the [oauthClient]
 * that presents it, and the [expiresAt] the access token was issued with.
 * Every OAuth field is optional and defaults to null, so a payload written
 * before they existed — `{"authToken":"…"}` — still decodes.
 *
 * The access token is derived output; the refresh token and the client are the
 * durable secrets. [McpCredentialRefresher] persists a rotated refresh token
 * before it lets go of the old one.
 *
 * [toString] names which fields are present and never what they hold, so a
 * credential that reaches a log line, an exception message or a trace carries
 * no secret with it.
 *
 * @property authToken Sent as `Authorization: Bearer <authToken>`.
 * @property refreshToken Presented to [McpOAuthClient.tokenEndpoint] for the
 *   next access token. Null for a credential that cannot be refreshed.
 * @property expiresAt When [authToken] stops working, if the issuer said.
 * @property oauthClient The OAuth client the refresh token was issued to.
 */
@Serializable
data class McpCredential(
    val authToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Instant? = null,
    val oauthClient: McpOAuthClient? = null,
) {

    /** Whether a refresh can mint a new [authToken]: it takes a refresh token and a client to present it. */
    val isRefreshable: Boolean
        get() = refreshToken != null && oauthClient != null

    /** Whether [authToken] is expired at [now], or will be within [margin]. False when no expiry is known. */
    fun expiresWithin(margin: Duration, now: Instant): Boolean {
        val expiry = expiresAt ?: return false
        return now >= expiry - margin
    }

    override fun toString(): String =
        "McpCredential(authToken=${redacted(authToken)}, refreshToken=${redacted(refreshToken)}, " +
            "expiresAt=$expiresAt, oauthClient=$oauthClient)"
}

/**
 * The OAuth 2.0 client a refresh token was issued to, and where to present it.
 *
 * @property tokenEndpoint The issuer's token endpoint (RFC 6749 §3.2).
 * @property clientId The client identifier. Not a secret by the RFC, but it
 *   names an operator's app, so [toString] keeps it out of logs too.
 * @property clientSecret Null for a public client, e.g. one that used PKCE.
 */
@Serializable
data class McpOAuthClient(
    val tokenEndpoint: String,
    val clientId: String,
    val clientSecret: String? = null,
) {
    override fun toString(): String =
        "McpOAuthClient(tokenEndpoint=$tokenEndpoint, clientId=${redacted(clientId)}, " +
            "clientSecret=${redacted(clientSecret)})"
}

/** What a credential field's `toString` shows: whether it is set, never its value. */
internal fun redacted(value: String?): String = if (value == null) "null" else "<redacted>"

/**
 * Storage contract for credentials a plug uses to talk to an MCP server.
 *
 * Implementations are scoped per Link so credentials revocations follow the
 * Link lifecycle. The interface intentionally avoids leaking implementation
 * concerns (transactionality, persistence, encryption).
 *
 * Two implementations: [KeychainMcpCredentialBinding], the persistent one,
 * which keeps each credential in the platform keychain under the Link's
 * [link.socket.ampere.link.CredentialRef] alias, and
 * [InMemoryMcpCredentialBinding], its twin for tests and one-shot processes.
 *
 * [bind] replaces whatever was bound for the pair in one step. That is what
 * lets [McpCredentialRefresher] persist a rotated refresh token without ever
 * dropping the old one first: never [unbind] to make room for a [bind].
 */
interface McpCredentialBinding {
    suspend fun bind(linkId: LinkId, mcpUri: String, credential: McpCredential): Result<Unit>

    suspend fun resolve(linkId: LinkId, mcpUri: String): Result<McpCredential?>

    suspend fun unbind(linkId: LinkId, mcpUri: String): Result<Unit>
}

/**
 * In-memory [McpCredentialBinding] suitable for tests and single-process
 * environments. Everything it holds is gone when the process exits; the
 * persistent implementation is [KeychainMcpCredentialBinding].
 */
class InMemoryMcpCredentialBinding : McpCredentialBinding {

    private val mutex = Mutex()
    private val store = mutableMapOf<Pair<LinkId, String>, McpCredential>()

    override suspend fun bind(
        linkId: LinkId,
        mcpUri: String,
        credential: McpCredential,
    ): Result<Unit> = mutex.withLock {
        store[linkId to mcpUri] = credential
        Result.success(Unit)
    }

    override suspend fun resolve(
        linkId: LinkId,
        mcpUri: String,
    ): Result<McpCredential?> = mutex.withLock {
        Result.success(store[linkId to mcpUri])
    }

    override suspend fun unbind(
        linkId: LinkId,
        mcpUri: String,
    ): Result<Unit> = mutex.withLock {
        store.remove(linkId to mcpUri)
        Result.success(Unit)
    }
}
