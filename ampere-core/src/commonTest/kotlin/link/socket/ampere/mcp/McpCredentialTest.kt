package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

class McpCredentialTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private val oauth = McpCredential(
        authToken = "fake-access-0",
        refreshToken = "fake-refresh-0",
        expiresAt = Instant.parse("2026-10-30T12:00:00Z"),
        oauthClient = fakeClient,
    )

    @Test
    fun `an authToken-only payload written before the OAuth fields still decodes`() {
        val decoded = json.decodeFromString(McpCredential.serializer(), """{"authToken":"fake-legacy-token"}""")

        assertEquals(McpCredential(authToken = "fake-legacy-token"), decoded)
        assertNull(decoded.refreshToken)
        assertNull(decoded.expiresAt)
        assertNull(decoded.oauthClient)
        assertFalse(decoded.isRefreshable)
    }

    @Test
    fun `an OAuth credential round-trips through JSON`() {
        val encoded = json.encodeToString(McpCredential.serializer(), oauth)

        assertEquals(oauth, json.decodeFromString(McpCredential.serializer(), encoded))
    }

    @Test
    fun `a public client without a secret round-trips`() {
        val publicClient = oauth.copy(oauthClient = fakeClient.copy(clientSecret = null))
        val encoded = json.encodeToString(McpCredential.serializer(), publicClient)

        assertEquals(publicClient, json.decodeFromString(McpCredential.serializer(), encoded))
        assertTrue(publicClient.isRefreshable)
    }

    @Test
    fun `toString names the fields and never their secrets`() {
        val rendered = oauth.toString() + McpCredential(authToken = "fake-bare").toString()

        listOf("fake-access-0", "fake-refresh-0", "fake-client-id", "fake-client-secret", "fake-bare").forEach {
            assertFalse(it in rendered, "toString leaked $it: $rendered")
        }
        assertTrue("2026-10-30T12:00:00Z" in rendered, rendered)
        assertTrue(fakeClient.tokenEndpoint in rendered, rendered)
    }

    @Test
    fun `refreshable needs both a refresh token and a client`() {
        assertTrue(oauth.isRefreshable)
        assertFalse(oauth.copy(refreshToken = null).isRefreshable)
        assertFalse(oauth.copy(oauthClient = null).isRefreshable)
    }

    @Test
    fun `expiresWithin counts the margin and treats an unknown expiry as never`() {
        val expiringSoon = oauth.copy(expiresAt = now + 4.minutes)

        assertTrue(expiringSoon.expiresWithin(5.minutes, now))
        assertFalse(expiringSoon.expiresWithin(3.minutes, now))
        assertTrue(oauth.copy(expiresAt = now - 1.minutes).expiresWithin(0.minutes, now))
        assertFalse(oauth.copy(expiresAt = null).expiresWithin(5.minutes, now))
    }
}
