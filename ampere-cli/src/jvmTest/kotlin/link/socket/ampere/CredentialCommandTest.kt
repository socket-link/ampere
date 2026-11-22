package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.InMemoryMcpCredentialBinding
import link.socket.ampere.mcp.McpAuthorizationServer
import link.socket.ampere.mcp.McpCredential
import link.socket.ampere.mcp.McpGrantedScope
import link.socket.ampere.mcp.McpOAuthAuthorizer
import link.socket.ampere.mcp.McpOAuthClient
import link.socket.ampere.mcp.OAuthBrowserSession
import link.socket.ampere.mcp.OAuthBrowserStep
import link.socket.ampere.mcp.OAuthClientRegistrar
import link.socket.ampere.mcp.OAuthClientRegistration
import link.socket.ampere.mcp.OAuthCodeExchange
import link.socket.ampere.mcp.OAuthDiscovery
import link.socket.ampere.mcp.OAuthRedirect
import link.socket.ampere.mcp.OAuthRegistrationResult
import link.socket.ampere.mcp.OAuthRevocationResult
import link.socket.ampere.mcp.OAuthTokenEndpoint
import link.socket.ampere.mcp.OAuthTokenResult
import link.socket.ampere.time.MutableClock
import org.junit.jupiter.api.Test

/**
 * The bootstrap (AMPR-464): a token response piped in, stored, and never printed.
 * And the interactive path (AMPR-516): the server's own authorization page, with
 * the network and the browser faked.
 */
class CredentialCommandTest {

    private val uri = "https://mcp.invalid/mcp"
    private val workSourceLink = LinkId("mcp:work-source")
    private val endpoint = "https://issuer.invalid/oauth/token"
    private val clock = MutableClock(Instant.parse("2026-10-29T12:00:00Z"))
    private val binding = InMemoryMcpCredentialBinding()

    private val tokenResponse = """
        {"access_token":"fake-access-0","token_type":"Bearer","expires_in":86399,
         "refresh_token":"fake-refresh-0","scope":"read,write"}
    """.trimIndent()

    private val secrets = listOf("fake-access-0", "fake-refresh-0", "fake-client-id", "fake-client-secret")

    private val issuer = FakeIssuer()
    private val opened = mutableListOf<String>()

    private fun command(
        stdin: String = tokenResponse,
        environment: Map<String, String> = mapOf(CredentialCommand.CLIENT_SECRET_ENV to "fake-client-secret"),
    ) = CredentialCommand(
        binding = { binding },
        environment = { environment[it] },
        readStdin = { stdin },
        clock = clock,
        authorization = { stored ->
            CredentialAuthorization(McpOAuthAuthorizer(stored, issuer, issuer, issuer, clock))
        },
        browser = { show, _ -> FakeBrowser(show) },
        openBrowser = { url -> opened.add(url) },
    )

    /**
     * An authorization server shaped like the one AMPR-516 was written against: it
     * registers a client, grants one scope more than was asked for, and issues thirty
     * days with no refresh token and no way to revoke — unless a test says otherwise.
     */
    private class FakeIssuer(
        var offersRegistration: Boolean = true,
        var offersRevocation: Boolean = false,
        var issuesRefreshToken: Boolean = false,
    ) : OAuthDiscovery, OAuthClientRegistrar, OAuthTokenEndpoint {

        var registrations = 0
        val exchangedAs = mutableListOf<McpOAuthClient>()
        val revoked = mutableListOf<String>()

        override suspend fun discover(mcpUri: String, wwwAuthenticate: String?) = Result.success(
            McpAuthorizationServer(
                mcpUri = mcpUri,
                resource = mcpUri,
                issuer = ISSUER,
                authorizationEndpoint = "$ISSUER/authorize",
                tokenEndpoint = "$ISSUER/token",
                registrationEndpoint = "$ISSUER/register".takeIf { offersRegistration },
                revocationEndpoint = "$ISSUER/revoke".takeIf { offersRevocation },
                scopesSupported = setOf("read", "operate"),
                issParameterSupported = true,
                refreshTokenGrantSupported = issuesRefreshToken,
            ),
        )

        override suspend fun register(
            registrationEndpoint: String,
            registration: OAuthClientRegistration,
        ): OAuthRegistrationResult {
            registrations += 1
            return OAuthRegistrationResult.Registered("fake-registered-client")
        }

        override suspend fun refresh(client: McpOAuthClient, refreshToken: String): OAuthTokenResult =
            error("no refresh expected")

        override suspend fun exchangeCode(client: McpOAuthClient, exchange: OAuthCodeExchange): OAuthTokenResult {
            exchangedAs += client
            return OAuthTokenResult.Issued(
                accessToken = "fake-access-1",
                refreshToken = "fake-refresh-1".takeIf { issuesRefreshToken },
                expiresIn = 2_592_000,
                scopes = setOf("read", "operate"),
            )
        }

        override suspend fun revoke(
            client: McpOAuthClient,
            token: String,
            tokenTypeHint: String,
        ): OAuthRevocationResult {
            revoked += token
            return OAuthRevocationResult.Revoked
        }
    }

    /** The browser step, faked: the URL is shown, and the "person" approves at once. */
    private class FakeBrowser(private val show: suspend (String) -> Unit) : OAuthBrowserStep {

        override suspend fun begin(): Result<OAuthBrowserSession> = Result.success(
            object : OAuthBrowserSession {
                override val redirectUri = "http://127.0.0.1:49152/oauth/callback"

                override suspend fun authorize(authorizationUrl: String): Result<OAuthRedirect> {
                    show(authorizationUrl)
                    val state = Regex("[?&]state=([^&]*)").find(authorizationUrl)!!.groupValues[1]
                    return Result.success(OAuthRedirect("$redirectUri?code=fake-code-1&state=$state&iss=$ISSUER"))
                }

                override suspend fun close() = Unit
            },
        )
    }

    private fun stored(link: LinkId = workSourceLink): McpCredential? =
        runBlocking { binding.resolve(link, uri).getOrThrow() }

    @Test
    fun `seed stores the piped token response under the work-source Link`() {
        val result = command().test(
            "seed --work-source $uri --token-endpoint $endpoint --client-id fake-client-id",
        )

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            McpCredential(
                authToken = "fake-access-0",
                refreshToken = "fake-refresh-0",
                expiresAt = clock.now() + 86_399.seconds,
                oauthClient = McpOAuthClient(endpoint, "fake-client-id", "fake-client-secret"),
                grantedScope = McpGrantedScope(setOf("read", "write"), statedByServer = true),
            ),
            stored(),
        )
        assertTrue("ampere.link:mcp:work-source" in result.output, result.output)
        assertTrue("Refresh:      yes" in result.output, result.output)
    }

    @Test
    fun `seed never prints a token or a client credential`() {
        val result = command().test(
            "seed --work-source $uri --token-endpoint $endpoint --client-id fake-client-id",
        )

        secrets.forEach { assertFalse(it in result.output + result.stderr, "printed $it: ${result.output}") }
    }

    @Test
    fun `a public client is seeded without a secret`() {
        val result = command(environment = emptyMap()).test(
            "seed --work-source $uri --token-endpoint $endpoint --client-id fake-client-id",
        )

        assertEquals(0, result.statusCode, result.stderr)
        assertNull(stored()?.oauthClient?.clientSecret)
    }

    @Test
    fun `seed honours an explicit Link`() {
        command().test(
            "seed --work-source $uri --link mcp:elsewhere --token-endpoint $endpoint --client-id fake-client-id",
        )

        assertNotNull(stored(LinkId("mcp:elsewhere")))
        assertNull(stored())
    }

    @Test
    fun `an error response on stdin stores nothing`() {
        val result = command(stdin = """{"error":"invalid_grant","error_description":"code fake-code expired"}""")
            .test("seed --work-source $uri --token-endpoint $endpoint --client-id fake-client-id")

        assertEquals(1, result.statusCode)
        assertTrue("invalid_grant" in result.stderr, result.stderr)
        assertFalse("fake-code" in result.stderr, result.stderr)
        assertNull(stored())
    }

    @Test
    fun `empty or malformed stdin stores nothing`() {
        val empty = command(stdin = "").test("seed --work-source $uri --token-endpoint $endpoint --client-id x")
        val garbage = command(stdin = "fake-access-pasted-alone")
            .test("seed --work-source $uri --token-endpoint $endpoint --client-id x")

        assertEquals(1, empty.statusCode)
        assertEquals(1, garbage.statusCode)
        assertFalse("fake-access-pasted-alone" in garbage.stderr, garbage.stderr)
        assertNull(stored())
    }

    @Test
    fun `a refresh token without a client to present it is refused`() {
        val result = command().test("seed --work-source $uri")

        assertEquals(1, result.statusCode)
        assertTrue("--token-endpoint" in result.stderr, result.stderr)
        assertNull(stored())
    }

    @Test
    fun `half a client is refused`() {
        val result = command().test("seed --work-source $uri --token-endpoint $endpoint")

        assertEquals(1, result.statusCode)
        assertNull(stored())
    }

    @Test
    fun `a bare access token can be seeded and is reported as not refreshable`() {
        val result = command(stdin = """{"access_token":"fake-access-0","expires_in":3600}""")
            .test("seed --work-source $uri")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("fake-access-0", stored()?.authToken)
        assertTrue("cannot be refreshed" in result.output, result.output)
    }

    @Test
    fun `status shows what is stored without showing it`() {
        command().test("seed --work-source $uri --token-endpoint $endpoint --client-id fake-client-id")
        clock.advance(3600.seconds)

        val result = command().test("status --work-source $uri")

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("Access token: stored" in result.output, result.output)
        assertTrue("in 22h 59m" in result.output, result.output)
        assertTrue(endpoint in result.output, result.output)
        secrets.forEach { assertFalse(it in result.output, "printed $it: ${result.output}") }
    }

    @Test
    fun `status with nothing stored fails and says how to seed`() {
        val result = command().test("status --work-source $uri")

        assertEquals(1, result.statusCode)
        assertTrue("ampere credential seed" in result.stderr, result.stderr)
    }

    @Test
    fun `clear removes the stored credential`() {
        command().test("seed --work-source $uri --token-endpoint $endpoint --client-id fake-client-id")

        val result = command().test("clear --work-source $uri")

        assertEquals(0, result.statusCode, result.stderr)
        assertNull(stored())
    }

    @Test
    fun `authorize stores what the server granted and says how it differs from what was asked for`() {
        val result = command().test("authorize --work-source $uri --scope read --no-browser")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            McpCredential(
                authToken = "fake-access-1",
                expiresAt = clock.now() + 2_592_000.seconds,
                oauthClient = McpOAuthClient(
                    tokenEndpoint = "$ISSUER/token",
                    clientId = "fake-registered-client",
                    redirectUri = "http://127.0.0.1:49152/oauth/callback",
                ),
                grantedScope = McpGrantedScope(setOf("read", "operate"), setOf("read"), statedByServer = true),
            ),
            stored(),
        )
        assertTrue("Granted:      read operate" in result.output, result.output)
        assertTrue("more than was asked for: operate" in result.output, result.output)
        assertTrue("Expires:      2026-11-28T12:00:00Z" in result.output, result.output)
        assertTrue("Refresh:      no" in result.output, result.output)
        assertTrue("`ampere credential authorize` again when it expires" in result.output, result.output)
        assertTrue("stays in force until it expires or you remove it in the other app" in result.output, result.output)
        assertTrue(opened.isEmpty(), "--no-browser opens nothing")
    }

    @Test
    fun `authorize shows the URL and opens it in the browser`() {
        val result = command().test("authorize --work-source $uri")

        assertEquals(0, result.statusCode, result.stderr)
        val url = opened.single()
        assertTrue(url.startsWith("$ISSUER/authorize?"), url)
        assertTrue("code_challenge_method=S256" in url, url)
        assertTrue("  $url" in result.output, "the URL is printed for a browser that did not open")
        assertTrue("Opened it in your browser" in result.output, result.output)
        assertTrue(
            "scope=read+operate" in url || "scope=read%20operate" in url,
            "with no --scope, what the server says it needs is asked for: $url",
        )
    }

    @Test
    fun `authorize never prints a code or a token and a client id only inside the URL`() {
        val result = command().test("authorize --work-source $uri")
        val printed = result.output + result.stderr

        listOf("fake-code-1", "fake-access-1", "fake-refresh-1").forEach {
            assertFalse(it in printed, "printed $it: $printed")
        }
        val outsideTheUrl = printed.lines().filterNot { it.startsWith("  $ISSUER/authorize?") }
        assertFalse(outsideTheUrl.any { "fake-registered-client" in it }, printed)
    }

    @Test
    fun `authorize with a client id skips registration and takes the secret from the environment`() {
        val result = command().test("authorize --work-source $uri --client-id fake-client-id --no-browser")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(0, issuer.registrations)
        assertEquals("fake-client-id", issuer.exchangedAs.single().clientId)
        assertEquals("fake-client-secret", stored()?.oauthClient?.clientSecret)
        assertFalse("fake-client-secret" in result.output + result.stderr, result.output)
    }

    @Test
    fun `authorize with a refresh token reports a renewable grant`() {
        issuer.issuesRefreshToken = true

        val result = command().test("authorize --work-source $uri --no-browser")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("fake-refresh-1", stored()?.refreshToken)
        assertTrue("Refresh:      yes" in result.output, result.output)
        assertFalse("cannot be refreshed" in result.output, result.output)
    }

    @Test
    fun `an authorization that fails stores nothing and says what was missing`() {
        issuer.offersRegistration = false

        val result = command().test("authorize --work-source $uri --no-browser")

        assertEquals(1, result.statusCode)
        assertTrue("no client id was supplied" in result.stderr, result.stderr)
        assertTrue("Nothing was stored" in result.stderr, result.stderr)
        assertNull(stored())
    }

    @Test
    fun `status shows the grant and that it cannot be revoked from here`() {
        command().test("authorize --work-source $uri --scope read --no-browser")

        val result = command().test("status --work-source $uri")

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("Granted:      read operate" in result.output, result.output)
        assertTrue("more than was asked for: operate" in result.output, result.output)
        assertTrue("Revocation:   not offered by the issuer" in result.output, result.output)
        assertFalse("fake-access-1" in result.output || "fake-registered-client" in result.output, result.output)
    }

    @Test
    fun `clear revokes at the issuer when the issuer offers that`() {
        issuer.offersRevocation = true
        command().test("authorize --work-source $uri --no-browser")

        val result = command().test("clear --work-source $uri")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(listOf("fake-access-1"), issuer.revoked)
        assertTrue("revoked it at the issuer" in result.output, result.output)
        assertNull(stored())
    }

    @Test
    fun `clear says the grant remains when it cannot be revoked from here`() {
        command().test("authorize --work-source $uri --no-browser")

        val result = command().test("clear --work-source $uri")

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(issuer.revoked.isEmpty(), "no endpoint is guessed at")
        assertFalse("revoked" in result.output, "nothing was revoked, so nothing says so: ${result.output}")
        assertTrue("removed from this machine only" in result.output, result.output)
        assertTrue("stays in force until it expires at 2026-11-28T12:00:00Z" in result.output, result.output)
        assertNull(stored())
    }

    @Test
    fun `clear with nothing stored says so`() {
        val result = command().test("clear --work-source $uri")

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("No credential was stored" in result.output, result.output)
    }

    private companion object {
        const val ISSUER = "https://issuer.invalid"
    }
}
