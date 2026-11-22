package link.socket.ampere.mcp

import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.McpRevocation.GrantRemains

/**
 * What a host asks for when it sends the person to authorize a Link.
 *
 * @property scopes The scopes to ask for. Null leaves the choice to the
 *   server's own account of what is needed: the `scope` of its 401 challenge,
 *   else every scope its protected-resource metadata lists, else none.
 * @property clientId A client already registered with the authorization
 *   server. Supplying one skips dynamic registration.
 * @property clientSecret The secret of a supplied confidential client. Null
 *   for a public one, which is what registration asks for.
 * @property clientName What a dynamically registered client is called on the
 *   server's consent page.
 * @property wwwAuthenticate The `WWW-Authenticate` value of a 401 the host
 *   already received from the MCP endpoint, so discovery need not ask again.
 */
class McpAuthorizationRequest(
    val scopes: Set<String>? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val clientName: String = DEFAULT_CLIENT_NAME,
    val wwwAuthenticate: String? = null,
) {
    override fun toString(): String =
        "McpAuthorizationRequest(scopes=$scopes, clientId=${redacted(clientId)}, " +
            "clientSecret=${redacted(clientSecret)}, clientName=$clientName)"

    companion object {
        const val DEFAULT_CLIENT_NAME: String = "Ampere"
    }
}

/**
 * The production collaborators of [McpOAuthAuthorizer], over one Ktor client.
 *
 * A process that authorizes once and exits — a CLI — should [close] it: an
 * HTTP engine's idle threads can otherwise hold the process open after the
 * work is done. A long-lived host keeps one for as long as it runs.
 *
 * @param httpClient The client all three ask with. The default discovers the
 *   platform engine; tests pass one over a mock engine.
 */
class KtorOAuthEndpoints(
    private val httpClient: HttpClient = HttpClient(),
) {
    val discovery: OAuthDiscovery = KtorOAuthDiscovery(httpClient)
    val registrar: OAuthClientRegistrar = KtorOAuthClientRegistrar(httpClient)
    val tokenEndpoint: OAuthTokenEndpoint = KtorOAuthTokenEndpoint(httpClient)

    /** Release the HTTP client. Nothing built on these endpoints works afterwards. */
    fun close() {
        httpClient.close()
    }
}

/**
 * Obtains a Link's credential through its MCP server's own authorization page,
 * and lets it go again (AMPR-516).
 *
 * Given an MCP endpoint and an [OAuthBrowserStep], [authorize] runs the OAuth
 * 2.1 authorization-code flow an MCP server describes and binds the result
 * through the same [McpCredentialBinding] a seeded credential uses:
 *
 * 1. **Discovery** ([OAuthDiscovery]): from the 401's `resource_metadata`, or
 *    the well-known path, to the authorization server's metadata.
 * 2. **The client**: the one supplied; else the one stored with this Link's
 *    credential, when it was registered for the same token endpoint and
 *    redirect URI; else a new registration (RFC 7591) when the server offers
 *    one; else the stored client on whatever redirect URI it has.
 * 3. **The authorization request**: PKCE `S256`, a fresh `state`, and the
 *    `resource` the token is for (RFC 8707), handed to the host's browser step.
 * 4. **The redirect**: `state` must be the one sent, and `iss` the issuer
 *    discovery found whenever the server advertises it or sends it (RFC 9207).
 *    Either failing means the redirect is not acted on and no code is sent.
 * 5. **The exchange**, and the credential bound in one replace of whatever was
 *    stored, so a Link being re-authorized is never left with nothing.
 *
 * ### What is recorded is what was granted
 *
 * The token response decides the stored scope when it states one, and it may
 * state more than was asked for. The request is kept beside it, so the
 * returned [McpGrant] can say the two differ. The access token's expiry is
 * recorded too; with no refresh token it is when the Link stops, and the
 * client then reports [McpCredentialException.ReauthorizationRequired].
 *
 * ### Nothing leaks
 *
 * The authorization code, the PKCE verifier and `state` live in this call's
 * frame and in types whose `toString` shows none of them. Nothing here logs,
 * publishes an event, or puts any of them, or a token, in a failure message.
 *
 * @param binding Where the credential is stored. [authorize] only ever
 *   [binds][McpCredentialBinding.bind]; [revoke] is the one caller of unbind.
 */
class McpOAuthAuthorizer(
    private val binding: McpCredentialBinding,
    private val discovery: OAuthDiscovery,
    private val registrar: OAuthClientRegistrar,
    private val tokenEndpoint: OAuthTokenEndpoint,
    private val clock: Clock = Clock.System,
) {

    /** The production wiring: discovery, registration and the token endpoint over [endpoints]' one Ktor client. */
    constructor(
        binding: McpCredentialBinding,
        endpoints: KtorOAuthEndpoints,
        clock: Clock = Clock.System,
    ) : this(
        binding = binding,
        discovery = endpoints.discovery,
        registrar = endpoints.registrar,
        tokenEndpoint = endpoints.tokenEndpoint,
        clock = clock,
    )

    /** Where `state` and the PKCE verifier come from. A test pins it; nothing else should. */
    internal var randomBytes: (Int) -> ByteArray = ::secureRandomBytes

    /**
     * Send the person through [mcpUri]'s authorization page by way of
     * [browser], and bind the credential that comes back for [linkId].
     *
     * @return What was granted and when it stops, with no token in it. A
     *   failure is a [McpAuthorizationException] naming what was missing or
     *   refused, and leaves whatever credential was stored as it was.
     */
    suspend fun authorize(
        linkId: LinkId,
        mcpUri: String,
        browser: OAuthBrowserStep,
        request: McpAuthorizationRequest = McpAuthorizationRequest(),
    ): Result<McpGrant> {
        val server = discovery.discover(mcpUri, request.wwwAuthenticate).getOrElse { return Result.failure(it) }

        val session = browser.begin().getOrElse {
            return Result.failure(McpAuthorizationException.BrowserStepFailed(mcpUri, it))
        }
        return try {
            authorizeIn(session, linkId, server, request)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    private suspend fun authorizeIn(
        session: OAuthBrowserSession,
        linkId: LinkId,
        server: McpAuthorizationServer,
        request: McpAuthorizationRequest,
    ): Result<McpGrant> {
        val mcpUri = server.mcpUri
        val requested = request.scopes ?: server.challengedScopes ?: server.scopesSupported
        val client = clientFor(session, linkId, server, request, requested).getOrElse { return Result.failure(it) }

        val pkce = PkceChallenge.generate(randomBytes)
        val state = base64Url(randomBytes(STATE_BYTES))

        val redirect = session.authorize(authorizationUrl(server, client, session, pkce, state, requested))
            .getOrElse { cause ->
                if (cause is CancellationException) throw cause
                return Result.failure(McpAuthorizationException.BrowserStepFailed(mcpUri, cause))
            }
        val code = codeFrom(redirect, server, state).getOrElse { return Result.failure(it) }

        val exchange = OAuthCodeExchange(
            code = code,
            codeVerifier = pkce.verifier,
            redirectUri = session.redirectUri,
            resource = server.resource,
        )
        val issued = when (val answer = tokenEndpoint.exchangeCode(client, exchange)) {
            is OAuthTokenResult.Issued -> answer
            is OAuthTokenResult.Rejected ->
                return Result.failure(McpAuthorizationException.TokenRejected(mcpUri, answer.error))
            is OAuthTokenResult.Unavailable ->
                return Result.failure(McpAuthorizationException.TokenUnavailable(mcpUri, answer.reason))
        }

        val credential = McpCredential(
            authToken = issued.accessToken,
            refreshToken = issued.refreshToken,
            expiresAt = issued.expiresIn?.let { clock.now() + it.seconds },
            oauthClient = client,
            grantedScope = McpGrantedScope(
                granted = issued.scopes ?: requested,
                requested = requested,
                statedByServer = issued.scopes != null,
            ),
        )
        persist(linkId, mcpUri, credential).getOrElse { return Result.failure(it) }
        return Result.success(credential.grant)
    }

    /**
     * Let the credential bound for [linkId] at [mcpUri] go, and say truthfully
     * what that did.
     *
     * When the authorization server advertised a revocation endpoint, the
     * refresh token and the access token are revoked there (RFC 7009) first.
     * The stored credential is then removed whatever the endpoint answered: the
     * person asked for this device to stop holding it. What the report says is
     * whether the grant on the server went with it. When it did not —
     * [McpRevocation.GrantRemains] — the grant is still in force until it
     * expires or the person removes it in the app that issued it, and a host
     * must say that rather than "revoked".
     *
     * A failure means the stored credential could not be read or removed.
     */
    suspend fun revoke(linkId: LinkId, mcpUri: String): Result<McpRevocation> {
        val stored = binding.resolve(linkId, mcpUri)
            .getOrElse { return Result.failure(McpCredentialException.Unreadable(linkId, it)) }
            ?: return Result.success(McpRevocation.NothingBound)

        // The refresh token first: revoking it ends the grant, where an access token alone may not.
        val revoking = stored.oauthClient?.takeIf { it.revocationEndpoint != null }
        val failures = if (revoking == null) {
            emptyList()
        } else {
            listOfNotNull(
                stored.refreshToken?.let { it to "refresh_token" },
                stored.authToken?.let { it to "access_token" },
            ).mapNotNull { (token, hint) ->
                tokenEndpoint.revoke(revoking, token, hint) as? OAuthRevocationResult.Failed
            }
        }

        binding.unbind(linkId, mcpUri).getOrElse { return Result.failure(it) }

        // A grant with a refresh token outlives its access token by a span the issuer never states.
        val remainsUntil = stored.expiresAt.takeIf { stored.refreshToken == null }
        return Result.success(
            when {
                revoking == null -> GrantRemains(GrantRemains.Reason.NO_REVOCATION_ENDPOINT, remainsUntil)
                failures.isNotEmpty() ->
                    GrantRemains(GrantRemains.Reason.REVOCATION_FAILED, remainsUntil, failures.first().reason)
                else -> McpRevocation.Revoked
            },
        )
    }

    private suspend fun clientFor(
        session: OAuthBrowserSession,
        linkId: LinkId,
        server: McpAuthorizationServer,
        request: McpAuthorizationRequest,
        requested: Set<String>,
    ): Result<McpOAuthClient> {
        fun McpOAuthClient.here() = copy(
            tokenEndpoint = server.tokenEndpoint,
            revocationEndpoint = server.revocationEndpoint,
            redirectUri = session.redirectUri,
        )

        request.clientId?.let { supplied ->
            return Result.success(McpOAuthClient(server.tokenEndpoint, supplied, request.clientSecret).here())
        }

        // A store that cannot be read has no client to offer; the bind at the end reports the store.
        val stored = binding.resolve(linkId, server.mcpUri).getOrNull()?.oauthClient
            ?.takeIf { it.tokenEndpoint == server.tokenEndpoint }
        if (stored != null && stored.redirectUri == session.redirectUri) return Result.success(stored.here())

        val registrationEndpoint = server.registrationEndpoint
            ?: return stored?.let { Result.success(it.here()) }
                ?: Result.failure(McpAuthorizationException.ClientRegistrationUnavailable(server.mcpUri))

        val registration = OAuthClientRegistration(
            clientName = request.clientName,
            redirectUri = session.redirectUri,
            grantTypes = listOfNotNull(
                "authorization_code",
                "refresh_token".takeIf { server.refreshTokenGrantSupported },
            ),
            scopes = requested,
        )
        return when (val answer = registrar.register(registrationEndpoint, registration)) {
            is OAuthRegistrationResult.Registered ->
                Result.success(McpOAuthClient(server.tokenEndpoint, answer.clientId, answer.clientSecret).here())
            is OAuthRegistrationResult.Rejected -> Result.failure(
                McpAuthorizationException.ClientRegistrationFailed(server.mcpUri, answer.error, refused = true),
            )
            is OAuthRegistrationResult.Unavailable -> Result.failure(
                McpAuthorizationException.ClientRegistrationFailed(server.mcpUri, answer.reason, refused = false),
            )
        }
    }

    private fun authorizationUrl(
        server: McpAuthorizationServer,
        client: McpOAuthClient,
        session: OAuthBrowserSession,
        pkce: PkceChallenge,
        state: String,
        requested: Set<String>,
    ): String = URLBuilder(server.authorizationEndpoint).apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", client.clientId)
        parameters.append("redirect_uri", session.redirectUri)
        parameters.append("state", state)
        parameters.append("code_challenge", pkce.challenge)
        parameters.append("code_challenge_method", PkceChallenge.METHOD)
        parameters.append("resource", server.resource)
        if (requested.isNotEmpty()) parameters.append("scope", requested.joinToString(" "))
    }.buildString()

    /**
     * The authorization code in [redirect], once the redirect is known to
     * answer this request and to come from this server. Nothing from the
     * redirect is quoted in a failure but an error code, reduced to safe
     * characters.
     */
    private fun codeFrom(redirect: OAuthRedirect, server: McpAuthorizationServer, state: String): Result<String> {
        val mcpUri = server.mcpUri
        val query = redirect.uri.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
        val response = runCatching { parseQueryString(query) }.getOrElse {
            return Result.failure(McpAuthorizationException.NoAuthorizationCode(mcpUri))
        }

        if (response["state"] != state) return Result.failure(McpAuthorizationException.StateMismatch(mcpUri))

        // RFC 9207 §2.4: compared as strings, before anything else in the response is believed.
        val issuer = response["iss"]
        if (issuer != null && issuer != server.issuer) {
            return Result.failure(McpAuthorizationException.IssuerMismatch(mcpUri, server.issuer, presented = true))
        }
        if (issuer == null && server.issParameterSupported) {
            return Result.failure(McpAuthorizationException.IssuerMismatch(mcpUri, server.issuer, presented = false))
        }

        response["error"]?.let { error ->
            return Result.failure(McpAuthorizationException.AuthorizationDenied(mcpUri, sanitizeOAuthError(error)))
        }

        val code = response["code"]?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(McpAuthorizationException.NoAuthorizationCode(mcpUri))
        return Result.success(code)
    }

    /** One replace of the stored credential, tried again on failure: the code behind it cannot be redeemed twice. */
    private suspend fun persist(linkId: LinkId, mcpUri: String, credential: McpCredential): Result<Unit> {
        var lastFailure: Throwable? = null
        repeat(PERSIST_ATTEMPTS) {
            binding.bind(linkId, mcpUri, credential)
                .onSuccess { return Result.success(Unit) }
                .onFailure { lastFailure = it }
        }
        return Result.failure(
            McpAuthorizationException.PersistFailed(
                mcpUri = mcpUri,
                cause = lastFailure ?: IllegalStateException("the binding refused the write"),
            ),
        )
    }

    private companion object {

        /** 183 random bits behind `state`; see [secureRandomBytes]. */
        const val STATE_BYTES = 24

        const val PERSIST_ATTEMPTS = 3
    }
}
