package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.McpCredential
import link.socket.ampere.mcp.McpCredentialBinding
import link.socket.ampere.mcp.McpCredentialRefresher
import link.socket.ampere.mcp.McpOAuthClient
import link.socket.ampere.mcp.OAuthTokenResult

/**
 * `ampere credential` — seed, inspect and clear a Link's MCP credential in the
 * keychain (AMPR-464).
 *
 * `seed` is the bootstrap: it reads the body of an OAuth token response on
 * stdin, so the operator pipes the code exchange straight into it and no token
 * is ever written to a file, an argument list or shell history. The client
 * secret, when the client has one, comes from `$AMPERE_OAUTH_CLIENT_SECRET` for
 * the same reason. The authorization step that produces the code stays manual
 * for v1; see `ampere-cli/README.md` for the command sequence.
 *
 * ```
 * curl … | ampere credential seed --work-source <uri> --token-endpoint <url> --client-id <id>
 * ampere credential status --work-source <uri>
 * ampere credential clear --work-source <uri>
 * ```
 *
 * Every command defaults `--link` to the work-source Link, `mcp:work-source`.
 * Nothing here prints a token, a secret or a client id.
 */
class CredentialCommand(
    binding: () -> McpCredentialBinding = { WorkSourceCredentials.keychainBinding() },
    environment: (String) -> String? = System::getenv,
    readStdin: () -> String = { System.`in`.bufferedReader().readText() },
    clock: Clock = Clock.System,
) : CliktCommand(
    name = "credential",
    help = "Seed, inspect or clear a Link's credential in the keychain",
) {

    init {
        val io = CredentialIo(binding, environment, readStdin, clock)
        subcommands(CredentialSeedCommand(io), CredentialStatusCommand(io), CredentialClearCommand(io))
    }

    override fun run() = Unit

    companion object {

        /** Where `seed` reads a confidential client's secret from: never an argument. */
        const val CLIENT_SECRET_ENV = "AMPERE_OAUTH_CLIENT_SECRET"
    }
}

/** What the `credential` subcommands reach outside themselves for: one seam, for tests. */
private class CredentialIo(
    val binding: () -> McpCredentialBinding,
    val environment: (String) -> String?,
    val readStdin: () -> String,
    val clock: Clock,
)

/** The Link and server every `credential` subcommand addresses, and the lines they print about it. */
private abstract class LinkCredentialCommand(
    name: String,
    help: String,
    protected val io: CredentialIo,
) : CliktCommand(name = name, help = help) {

    protected val workSource by option(
        "--work-source",
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

    protected fun expiryLine(credential: McpCredential): String {
        val expiresAt = credential.expiresAt ?: return "  Expires:      not stated by the issuer"
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
            "  Refresh:      no"
        }
    }

    protected fun fail(message: String): Nothing =
        throw PrintMessage(message, statusCode = 1, printError = true)

    private fun rounded(duration: Duration): String = duration.inWholeMinutes.let { minutes ->
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
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
                "(keychain service ${CredentialRef.aliasFor(linkId)}). Seed one with `ampere credential seed`.",
        )

        echo("Link ${linkId.value} at $workSource")
        echo(keychainLine())
        echo("  Access token: ${if (credential.authToken != null) "stored" else "none"}")
        echo(expiryLine(credential))
        echo(refreshLine(credential))
    }
}

private class CredentialClearCommand(io: CredentialIo) : LinkCredentialCommand(
    name = "clear",
    help = "Remove the stored credential",
    io = io,
) {
    override fun run() = runBlocking {
        io.binding().unbind(linkId, workSource).getOrElse { cause ->
            fail("Could not remove the credential: ${cause.message}")
        }
        echo("Cleared the credential for Link ${linkId.value} at $workSource")
    }
}
