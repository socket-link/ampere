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
    fun `a credential written before the grant fields still decodes`() {
        // What `main` wrote before AMPR-516: nulls left out, no grantedScope, a three-field client.
        val writtenBefore = """
            {"authToken":"fake-access-0","refreshToken":"fake-refresh-0","expiresAt":"2026-10-30T12:00:00Z",
             "oauthClient":{"tokenEndpoint":"https://issuer.invalid/oauth/token","clientId":"fake-client-id",
             "clientSecret":"fake-client-secret"}}
        """.trimIndent()

        val decoded = json.decodeFromString(McpCredential.serializer(), writtenBefore)

        assertEquals(oauth, decoded)
        assertNull(decoded.grantedScope)
        assertNull(decoded.oauthClient?.revocationEndpoint)
        assertNull(decoded.oauthClient?.redirectUri)
        assertTrue(decoded.isRefreshable)
    }

    @Test
    fun `a credential with its grant round-trips`() {
        val granted = oauth.copy(
            refreshToken = null,
            oauthClient = fakeClient.copy(
                clientSecret = null,
                revocationEndpoint = "https://issuer.invalid/oauth/revoke",
                redirectUri = FAKE_REDIRECT_URI,
            ),
            grantedScope = McpGrantedScope(setOf("read", "operate"), setOf("read"), statedByServer = true),
        )
        val encoded = json.encodeToString(McpCredential.serializer(), granted)

        assertEquals(granted, json.decodeFromString(McpCredential.serializer(), encoded))
        assertTrue("\"grantedScope\"" in encoded, encoded)
    }

    @Test
    fun `the grant is readable without the token`() {
        val seeded = oauth.grant
        val granted = oauth.copy(
            refreshToken = null,
            oauthClient = fakeClient.copy(revocationEndpoint = "https://issuer.invalid/oauth/revoke"),
            grantedScope = McpGrantedScope(setOf("read", "operate"), setOf("read"), statedByServer = true),
        ).grant

        assertNull(seeded.scopes, "nobody recorded a scope for a hand-seeded credential")
        assertTrue(seeded.renewable)
        assertFalse(seeded.revocable)
        assertNull(seeded.stopsWorkingAt, "a renewable grant does not stop at its access token's expiry")

        assertEquals(setOf("read", "operate"), granted.scopes)
        assertEquals(setOf("operate"), granted.beyondRequest)
        assertTrue(granted.withheld.isEmpty())
        assertTrue(granted.differsFromRequest)
        assertFalse(granted.renewable)
        assertTrue(granted.revocable)
        assertEquals(oauth.expiresAt, granted.stopsWorkingAt)
        assertFalse("fake-" in granted.toString(), granted.toString())
    }

    @Test
    fun `only an expired credential that cannot be refreshed needs authorizing again`() {
        val expired = now + 1.minutes
        val bare = McpCredential(authToken = "fake-access-0", expiresAt = now)

        assertTrue(bare.needsReauthorization(expired))
        assertFalse(bare.needsReauthorization(now - 1.minutes))
        assertFalse(bare.copy(expiresAt = null).needsReauthorization(expired), "no stated expiry: only a 401 can tell")
        assertFalse(oauth.copy(expiresAt = now).needsReauthorization(expired), "a refresh replaces it")
        assertTrue(oauth.copy(expiresAt = now, oauthClient = null).needsReauthorization(expired))
        assertFalse(McpCredential().needsReauthorization(expired))
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
