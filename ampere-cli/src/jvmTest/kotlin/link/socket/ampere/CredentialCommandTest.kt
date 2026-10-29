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
import link.socket.ampere.mcp.McpCredential
import link.socket.ampere.mcp.McpOAuthClient
import link.socket.ampere.time.MutableClock
import org.junit.jupiter.api.Test

/** The bootstrap (AMPR-464): a token response piped in, stored, and never printed. */
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

    private fun command(
        stdin: String = tokenResponse,
        environment: Map<String, String> = mapOf(CredentialCommand.CLIENT_SECRET_ENV to "fake-client-secret"),
    ) = CredentialCommand(
        binding = { binding },
        environment = { environment[it] },
        readStdin = { stdin },
        clock = clock,
    )

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
}
