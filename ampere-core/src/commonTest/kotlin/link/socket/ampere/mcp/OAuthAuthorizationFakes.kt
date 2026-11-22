package link.socket.ampere.mcp

import io.ktor.http.Url
import link.socket.ampere.link.LinkId

/** An obviously fake MCP endpoint and authorization server. Nothing here is, or resembles, a real one. */
internal const val FAKE_MCP_URI = "https://mcp.invalid/mcp"
internal const val FAKE_ISSUER = "https://auth.invalid"
internal const val FAKE_REDIRECT_URI = "http://127.0.0.1:49152/oauth/callback"

/**
 * An authorization server, as [McpOAuthAuthorizer] sees one through its three
 * ports, shaped like the server AMPR-516 was written against and configurable
 * away from it: it can issue no refresh token, grant more than was asked for,
 * offer no registration and advertise no revocation endpoint.
 *
 * It keeps its side of the protocol honestly: a code is single-use, and the
 * token endpoint refuses a `code_verifier` whose `S256` hash is not the
 * challenge the authorization request sent.
 */
internal class FakeAuthorizationServer(
    var registrationOffered: Boolean = true,
    var revocationOffered: Boolean = false,
    var issueRefreshToken: Boolean = false,
    var issAdvertised: Boolean = true,
    /** The scopes the token response states. Null states none. */
    var grantedScopes: ((requested: Set<String>) -> Set<String>?)? = { it + "operate" },
    var expiresIn: Long? = THIRTY_DAYS,
    var challengedScopes: Set<String>? = null,
    var scopesSupported: Set<String> = setOf("read", "operate"),
) : OAuthDiscovery, OAuthClientRegistrar, OAuthTokenEndpoint {

    val discoveries = mutableListOf<Pair<String, String?>>()
    val registrations = mutableListOf<OAuthClientRegistration>()
    val authorizationRequests = mutableListOf<Map<String, String>>()
    val exchanges = mutableListOf<OAuthCodeExchange>()
    val exchangeClients = mutableListOf<McpOAuthClient>()
    val revocations = mutableListOf<Pair<String, String>>()

    var discoveryFailure: McpAuthorizationException? = null
    var registrationAnswer: OAuthRegistrationResult? = null
    var tokenAnswer: OAuthTokenResult? = null
    var revocationAnswer: OAuthRevocationResult = OAuthRevocationResult.Revoked

    /** What the authorization page puts on the redirect instead of a code, e.g. `access_denied`. */
    var denyWith: String? = null

    private val pending = mutableMapOf<String, Map<String, String>>()
    private var issued = 0

    val description: McpAuthorizationServer
        get() = McpAuthorizationServer(
            mcpUri = FAKE_MCP_URI,
            resource = FAKE_MCP_URI,
            issuer = FAKE_ISSUER,
            authorizationEndpoint = "$FAKE_ISSUER/authorize",
            tokenEndpoint = "$FAKE_ISSUER/token",
            registrationEndpoint = "$FAKE_ISSUER/register".takeIf { registrationOffered },
            revocationEndpoint = "$FAKE_ISSUER/revoke".takeIf { revocationOffered },
            scopesSupported = scopesSupported,
            challengedScopes = challengedScopes,
            issParameterSupported = issAdvertised,
            refreshTokenGrantSupported = issueRefreshToken,
        )

    override suspend fun discover(mcpUri: String, wwwAuthenticate: String?): Result<McpAuthorizationServer> {
        discoveries += mcpUri to wwwAuthenticate
        discoveryFailure?.let { return Result.failure(it) }
        return Result.success(description.copy(mcpUri = mcpUri, resource = mcpUri))
    }

    override suspend fun register(
        registrationEndpoint: String,
        registration: OAuthClientRegistration,
    ): OAuthRegistrationResult {
        registrations += registration
        return registrationAnswer ?: OAuthRegistrationResult.Registered("fake-registered-client-${registrations.size}")
    }

    /**
     * The authorization page: the person approves, and the server sends them
     * back to the request's redirect URI with a code, the request's `state`,
     * and its own `iss`.
     */
    fun approve(authorizationUrl: String): String {
        val request = Url(authorizationUrl).parameters.let { all -> all.names().associateWith { all[it]!! } }
        authorizationRequests += request

        val answer = denyWith?.let { "error=$it" } ?: run {
            issued += 1
            "code=fake-code-$issued".also { pending["fake-code-$issued"] = request }
        }
        val issuer = if (issAdvertised) "&iss=$FAKE_ISSUER" else ""
        return "${request["redirect_uri"]}?$answer&state=${request["state"]}$issuer"
    }

    override suspend fun refresh(client: McpOAuthClient, refreshToken: String): OAuthTokenResult =
        error("no refresh expected")

    override suspend fun exchangeCode(client: McpOAuthClient, exchange: OAuthCodeExchange): OAuthTokenResult {
        exchanges += exchange
        exchangeClients += client
        tokenAnswer?.let { return it }

        val request = pending.remove(exchange.code) ?: return OAuthTokenResult.Rejected("invalid_grant")
        val sameRequest = request["redirect_uri"] == exchange.redirectUri &&
            request["client_id"] == client.clientId &&
            request["resource"] == exchange.resource &&
            request["code_challenge_method"] == "S256" &&
            request["code_challenge"] == PkceChallenge.of(exchange.codeVerifier).challenge
        if (!sameRequest) return OAuthTokenResult.Rejected("invalid_grant")

        val requested = request["scope"]?.let(::parseScopes).orEmpty()
        return OAuthTokenResult.Issued(
            accessToken = "fake-access-${exchanges.size}",
            refreshToken = "fake-refresh-${exchanges.size}".takeIf { issueRefreshToken },
            expiresIn = expiresIn,
            scopes = grantedScopes?.invoke(requested),
        )
    }

    override suspend fun revoke(client: McpOAuthClient, token: String, tokenTypeHint: String): OAuthRevocationResult {
        revocations += token to tokenTypeHint
        return revocationAnswer
    }

    companion object {
        const val THIRTY_DAYS: Long = 2_592_000
    }
}

/**
 * The browser step, faked: the "person" approves at once on [server]'s
 * authorization page. [tamper] rewrites the redirect on its way back, the way
 * an attacker or another tab's response would.
 */
internal class FakeBrowserStep(
    private val server: FakeAuthorizationServer,
    private val redirectUri: String = FAKE_REDIRECT_URI,
    var tamper: (String) -> String = { it },
) : OAuthBrowserStep {

    val shown = mutableListOf<String>()
    var sessions = 0
    var closed = 0

    var beginFailure: Throwable? = null
    var authorizeFailure: Throwable? = null

    override suspend fun begin(): Result<OAuthBrowserSession> {
        beginFailure?.let { return Result.failure(it) }
        sessions += 1
        return Result.success(
            object : OAuthBrowserSession {
                override val redirectUri: String = this@FakeBrowserStep.redirectUri

                override suspend fun authorize(authorizationUrl: String): Result<OAuthRedirect> {
                    shown += authorizationUrl
                    authorizeFailure?.let { return Result.failure(it) }
                    return Result.success(OAuthRedirect(tamper(server.approve(authorizationUrl))))
                }

                override suspend fun close() {
                    closed += 1
                }
            },
        )
    }
}

/** A binding that records every call, to show a re-authorization replaces and never unbinds. */
internal class RecordingBinding(
    private val delegate: McpCredentialBinding = InMemoryMcpCredentialBinding(),
) : McpCredentialBinding {

    val binds = mutableListOf<McpCredential>()
    var unbinds = 0

    /** How many upcoming binds to refuse, as a locked keychain would. */
    var refuseBinds = 0

    override suspend fun bind(linkId: LinkId, mcpUri: String, credential: McpCredential): Result<Unit> {
        if (refuseBinds > 0) {
            refuseBinds -= 1
            return Result.failure(IllegalStateException("the keychain is locked"))
        }
        binds += credential
        return delegate.bind(linkId, mcpUri, credential)
    }

    override suspend fun resolve(linkId: LinkId, mcpUri: String): Result<McpCredential?> =
        delegate.resolve(linkId, mcpUri)

    override suspend fun unbind(linkId: LinkId, mcpUri: String): Result<Unit> {
        unbinds += 1
        return delegate.unbind(linkId, mcpUri)
    }
}
