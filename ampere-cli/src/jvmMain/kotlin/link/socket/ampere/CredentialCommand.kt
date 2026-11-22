package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.KtorOAuthEndpoints
import link.socket.ampere.mcp.LoopbackOAuthBrowserStep
import link.socket.ampere.mcp.McpAuthorizationRequest
import link.socket.ampere.mcp.McpCredential
import link.socket.ampere.mcp.McpCredentialBinding
import link.socket.ampere.mcp.McpCredentialRefresher
import link.socket.ampere.mcp.McpGrant
import link.socket.ampere.mcp.McpGrantedScope
import link.socket.ampere.mcp.McpOAuthAuthorizer
import link.socket.ampere.mcp.McpOAuthClient
import link.socket.ampere.mcp.McpRevocation
import link.socket.ampere.mcp.OAuthBrowserStep
import link.socket.ampere.mcp.OAuthTokenResult
import link.socket.ampere.mcp.SystemBrowser

/**
 * `ampere credential` — authorize, seed, inspect and clear a Link's MCP
 * credential in the keychain (AMPR-464, AMPR-516).
 *
 * `authorize` is the interactive path: it finds the server's authorization
 * server from the endpoint itself, registers a client when the server offers
 * that, sends the operator to the authorization page, catches the redirect on
 * a loopback port and stores what was granted. No code is copied by hand, and
 * nothing it handles reaches an argument list, a file or shell history.
 *
 * `seed` is the bootstrap that predates it, kept for an issuer whose
 * authorization cannot be driven this way: it reads the body of an OAuth token
 * response on stdin, so the operator pipes a hand-run code exchange into it.
 * The client secret, when the client has one, comes from
 * `$AMPERE_OAUTH_CLIENT_SECRET` for both.
 *
 * ```
 * ampere credential authorize --work-source <uri>
 * curl … | ampere credential seed --work-source <uri> --token-endpoint <url> --client-id <id>
 * ampere credential status --work-source <uri>
 * ampere credential clear --work-source <uri>
 * ```
 *
 * Every command defaults `--link` to the work-source Link, `mcp:work-source`.
 * Nothing here prints a token, a secret or an authorization code. A client id
 * appears in one place only: inside the authorization URL `authorize` shows,
 * which is the address the operator's browser has to open.
 *
 * @param authorization The OAuth flow over [binding]. The default talks to the
 *   network through Ktor; a test passes fakes.
 * @param browser The browser step, given what puts a URL in front of the
 *   operator and how long to wait. The default is the loopback listener.
 * @param openBrowser Opens a URL in the system browser, reporting whether it
 *   could. A test passes a recorder so no browser opens.
 */
class CredentialCommand(
    binding: () -> McpCredentialBinding = { WorkSourceCredentials.keychainBinding() },
    environment: (String) -> String? = System::getenv,
    readStdin: () -> String = { System.`in`.bufferedReader().readText() },
    clock: Clock = Clock.System,
    authorization: (McpCredentialBinding) -> CredentialAuthorization = { CredentialAuthorization.overNetwork(it) },
    browser: (show: suspend (String) -> Unit, timeout: Duration) -> OAuthBrowserStep =
        { show, timeout -> LoopbackOAuthBrowserStep(openUrl = show, timeout = timeout) },
    openBrowser: (String) -> Boolean = { SystemBrowser.open(it) },
) : CliktCommand(
    name = "credential",
    help = "Authorize, seed, inspect or clear a Link's credential in the keychain",
) {

    init {
        val io = CredentialIo(binding, environment, readStdin, clock, authorization, browser, openBrowser)
        subcommands(
            CredentialAuthorizeCommand(io),
            CredentialSeedCommand(io),
            CredentialStatusCommand(io),
            CredentialClearCommand(io),
        )
    }

    override fun run() = Unit

    companion object {

        /** Where `seed` and `authorize` read a confidential client's secret from: never an argument. */
        const val CLIENT_SECRET_ENV = "AMPERE_OAUTH_CLIENT_SECRET"

        /** What a client `authorize` registers is called on the issuer's consent page. */
        const val CLIENT_NAME = "Ampere CLI"
    }
}

/**
 * The OAuth flow one `credential` command runs, and how to release what it
 * holds when the command is done.
 */
class CredentialAuthorization(
    val authorizer: McpOAuthAuthorizer,
    val close: () -> Unit = {},
) {
    companion object {

        /** Over the network. [close] releases the HTTP client, whose idle threads would otherwise delay exit. */
        fun overNetwork(binding: McpCredentialBinding): CredentialAuthorization {
            val endpoints = KtorOAuthEndpoints()
            return CredentialAuthorization(McpOAuthAuthorizer(binding, endpoints), close = endpoints::close)
        }
    }
}

/** What the `credential` subcommands reach outside themselves for: one seam, for tests. */
private class CredentialIo(
    val binding: () -> McpCredentialBinding,
    val environment: (String) -> String?,
    val readStdin: () -> String,
    val clock: Clock,
    val authorization: (McpCredentialBinding) -> CredentialAuthorization,
    val browser: (show: suspend (String) -> Unit, timeout: Duration) -> OAuthBrowserStep,
    val openBrowser: (String) -> Boolean,
) {
    /** Run [block] with the OAuth flow over the stored credentials, releasing it afterwards. */
    inline fun <T> withAuthorizer(block: (McpOAuthAuthorizer) -> T): T {
        val authorization = authorization(binding())
        return try {
            block(authorization.authorizer)
        } finally {
            authorization.close()
        }
    }
}

/** The Link and server every `credential` subcommand addresses, and the lines they print about it. */
private abstract class LinkCredentialCommand(
    name: String,
    help: String,
    protected val io: CredentialIo,
) : CliktCommand(name = name, help = help) {

    protected val workSource by option(
        "--work-source",
        "--server",
        help = "MCP server URI the credential is for (the keychain item's account)",
    ).required()

    private val link by option(
        "--link",
        help = "Link id (default: the work-source Link, mcp:work-source)",
    ).convert { LinkId(it) }

    protected val linkId: LinkId
        get() = link ?: WorkSourceCredentials.linkIdFor(workSource)

    protected fun keychainLine(): String =
        "  Keychain:     service ${CredentialRef.aliasFor(linkId)}, account $workSource"

    protected fun expiryLine(credential: McpCredential): String = expiryLine(credential.expiresAt)

    protected fun expiryLine(expiry: Instant?): String {
        val expiresAt = expiry ?: return "  Expires:      not stated by the issuer"
        val remaining = expiresAt - io.clock.now()
        val relative = if (remaining.isPositive()) "in ${rounded(remaining)}" else "expired ${rounded(-remaining)} ago"
        return "  Expires:      $expiresAt ($relative)"
    }

    protected fun refreshLine(credential: McpCredential): String {
        val client = credential.oauthClient
        return if (credential.isRefreshable && client != null) {
            "  Refresh:      yes, at ${client.tokenEndpoint}, within " +
                "${McpCredentialRefresher.DEFAULT_REFRESH_MARGIN} of expiry and after a 401"
        } else {
            refreshLine(renewable = false)
        }
    }

    protected fun refreshLine(renewable: Boolean): String =
        if (renewable) {
            "  Refresh:      yes, within ${McpCredentialRefresher.DEFAULT_REFRESH_MARGIN} of expiry and after a 401"
        } else {
            "  Refresh:      no"
        }

    /** What the credential grants, as the issuer stated it, and how that differs from what was asked for. */
    protected fun grantLines(grant: McpGrant): List<String> {
        val scopes = grant.scopes ?: return listOf("  Granted:      not recorded (the issuer stated no scope)")
        val granted = if (scopes.isEmpty()) "no scope" else scopes.joinToString(" ")
        return buildList {
            add(
                "  Granted:      $granted" +
                    if (grant.scopesStatedByServer) "" else " (as asked for; the issuer did not state the grant)",
            )
            if (grant.beyondRequest.isNotEmpty() && !grant.requestedScopes.isNullOrEmpty()) {
                add("                more than was asked for: ${grant.beyondRequest.joinToString(" ")}")
            }
            if (grant.withheld.isNotEmpty()) {
                add("                asked for and not granted: ${grant.withheld.joinToString(" ")}")
            }
        }
    }

    /** Whether letting the credential go also ends the grant at the issuer. Claims no revocation it cannot make. */
    protected fun revocationLine(grant: McpGrant): String =
        if (grant.revocable) {
            "  Revocation:   `ampere credential clear` revokes it at the issuer"
        } else {
            "  Revocation:   not offered by the issuer. `ampere credential clear` removes it here only; " +
                "the grant stays in force until it expires or you remove it in the other app"
        }

    protected fun fail(message: String): Nothing =
        throw PrintMessage(message, statusCode = 1, printError = true)

    private fun rounded(duration: Duration): String = duration.inWholeMinutes.let { minutes ->
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}

private class CredentialAuthorizeCommand(io: CredentialIo) : LinkCredentialCommand(
    name = "authorize",
    help = "Authorize the Link in a browser, through the server's own authorization page, and store what " +
        "was granted",
    io = io,
) {

    private val clientId by option(
        "--client-id",
        help = "An OAuth client already registered with the issuer. Without one, a client is registered " +
            "when the issuer offers that. Its secret, if any, comes from \$${CredentialCommand.CLIENT_SECRET_ENV}",
    )

    private val scopes by option(
        "--scope",
        help = "A scope to ask for; repeat for several. Default: what the server says it needs",
    ).multiple()

    private val noBrowser by option(
        "--no-browser",
        help = "Print the authorization URL and do not try to open it",
    ).flag()

    private val timeoutMinutes by option(
        "--timeout",
        help = "Minutes to wait for the redirect (default: 5)",
    ).int().default(DEFAULT_TIMEOUT_MINUTES)

    override fun run() = runBlocking {
        if (timeoutMinutes < 1) fail("--timeout must be at least 1 minute")

        val asked = scopes.flatMap { it.split(' ', ',') }.filterTo(LinkedHashSet()) { it.isNotEmpty() }
        val request = McpAuthorizationRequest(
            scopes = asked.takeIf { it.isNotEmpty() },
            clientId = clientId,
            clientSecret = clientId
                ?.let { io.environment(CredentialCommand.CLIENT_SECRET_ENV) }
                ?.takeIf { it.isNotBlank() },
            clientName = CredentialCommand.CLIENT_NAME,
        )

        // The URL carries this request's state, PKCE challenge and client id, and no credential: it is
        // what the browser's address bar shows. It is printed so it can be opened by hand when no
        // browser opens by itself.
        val show: suspend (String) -> Unit = { url ->
            echo("Authorize Link ${linkId.value} at $workSource by opening:")
            echo("  $url")
            val opened = !noBrowser && io.openBrowser(url)
            echo(
                (if (opened) "Opened it in your browser. " else "") +
                    "Waiting up to $timeoutMinutes min for the redirect…",
            )
        }

        val grant = io.withAuthorizer { authorizer ->
            authorizer.authorize(linkId, workSource, io.browser(show, timeoutMinutes.minutes), request)
        }.getOrElse { cause ->
            fail("${cause.message}\nNothing was stored, and whatever was stored before is unchanged.")
        }

        echo("Authorized Link ${linkId.value} at $workSource")
        echo(keychainLine())
        grantLines(grant).forEach { echo(it) }
        echo(expiryLine(grant.expiresAt))
        echo(refreshLine(grant.renewable))
        echo(revocationLine(grant))
        if (!grant.renewable) {
            echo("  This credential cannot be refreshed: run `ampere credential authorize` again when it expires.")
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
    }
}

private class CredentialSeedCommand(io: CredentialIo) : LinkCredentialCommand(
    name = "seed",
    help = "Store the OAuth token response read from stdin. The client secret, if any, comes from " +
        "\$${CredentialCommand.CLIENT_SECRET_ENV}",
    io = io,
) {

    private val tokenEndpoint by option(
        "--token-endpoint",
        help = "OAuth token endpoint the refresh token is presented to",
    )

    private val clientId by option(
        "--client-id",
        help = "OAuth client id the tokens were issued to",
    )

    override fun run() = runBlocking {
        if ((tokenEndpoint == null) != (clientId == null)) {
            fail("--token-endpoint and --client-id go together: a refresh needs both")
        }

        val body = io.readStdin().trim()
        if (body.isEmpty()) {
            fail("Expected the token endpoint's JSON response on stdin, and stdin was empty. Nothing was stored.")
        }

        val issued = when (val parsed = OAuthTokenResult.parse(body)) {
            is OAuthTokenResult.Issued -> parsed
            is OAuthTokenResult.Rejected ->
                fail("The token response on stdin is an error (${parsed.error}). Nothing was stored.")
            is OAuthTokenResult.Unavailable ->
                fail("Stdin is not a token response (a JSON object with an access_token). Nothing was stored.")
        }

        val client = tokenEndpoint?.let { endpoint ->
            McpOAuthClient(
                tokenEndpoint = endpoint,
                clientId = requireNotNull(clientId),
                clientSecret = io.environment(CredentialCommand.CLIENT_SECRET_ENV)?.takeIf { it.isNotBlank() },
            )
        }
        if (issued.refreshToken != null && client == null) {
            fail(
                "The token response carries a refresh token, so --token-endpoint and --client-id are needed " +
                    "to use it. Nothing was stored.",
            )
        }

        val now = io.clock.now()
        val credential = McpCredential(
            authToken = issued.accessToken,
            refreshToken = issued.refreshToken,
            expiresAt = issued.expiresIn?.let { now + it.seconds },
            oauthClient = client,
            // What the piped response says was granted. What was asked for is not on stdin.
            grantedScope = issued.scopes?.let { McpGrantedScope(granted = it, statedByServer = true) },
        )

        io.binding().bind(linkId, workSource, credential).getOrElse { cause ->
            fail("Could not store the credential: ${cause.message}")
        }

        echo("Seeded the credential for Link ${linkId.value} at $workSource")
        echo(keychainLine())
        echo(expiryLine(credential))
        echo(refreshLine(credential))
        if (!credential.isRefreshable) {
            echo("  This credential cannot be refreshed: seed it again before it expires.")
        }
    }
}

private class CredentialStatusCommand(io: CredentialIo) : LinkCredentialCommand(
    name = "status",
    help = "Show whether a credential is stored and when it expires, without showing it",
    io = io,
) {
    override fun run() = runBlocking {
        val credential = io.binding().resolve(linkId, workSource).getOrElse { cause ->
            fail("Could not read the credential: ${cause.message}")
        } ?: fail(
            "No credential is stored for Link ${linkId.value} at $workSource " +
                "(keychain service ${CredentialRef.aliasFor(linkId)}). Run `ampere credential authorize`, " +
                "or seed one with `ampere credential seed`.",
        )

        echo("Link ${linkId.value} at $workSource")
        echo(keychainLine())
        echo("  Access token: ${if (credential.authToken != null) "stored" else "none"}")
        grantLines(credential.grant).forEach { echo(it) }
        echo(expiryLine(credential))
        echo(refreshLine(credential))
        echo(revocationLine(credential.grant))
    }
}

private class CredentialClearCommand(io: CredentialIo) : LinkCredentialCommand(
    name = "clear",
    help = "Remove the stored credential, revoking it at the issuer when the issuer offers that",
    io = io,
) {
    override fun run() = runBlocking {
        val outcome = io.withAuthorizer { authorizer -> authorizer.revoke(linkId, workSource) }
            .getOrElse { cause -> fail("Could not remove the credential: ${cause.message}") }

        when (outcome) {
            McpRevocation.NothingBound ->
                echo("No credential was stored for Link ${linkId.value} at $workSource")

            McpRevocation.Revoked ->
                echo("Cleared the credential for Link ${linkId.value} at $workSource, and revoked it at the issuer")

            is McpRevocation.GrantRemains -> {
                echo("Cleared the credential for Link ${linkId.value} at $workSource")
                val why = when (outcome.reason) {
                    McpRevocation.GrantRemains.Reason.NO_REVOCATION_ENDPOINT ->
                        "the issuer offers no way to revoke it from here"
                    McpRevocation.GrantRemains.Reason.REVOCATION_FAILED ->
                        "the issuer did not confirm the revocation (${outcome.detail})"
                }
                val until = outcome.remainsUntil?.let { "until it expires at $it" } ?: "until it expires"
                echo("  It was removed from this machine only: $why.")
                echo("  The grant stays in force $until, or until you remove it in the other app.")
            }
        }
    }
}
