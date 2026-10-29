package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.link.LinkId
import link.socket.ampere.time.MutableClock

class McpCredentialRefresherTest {

    private val linkId = LinkId("mcp:work-source")
    private val mcpUri = "https://mcp.invalid/mcp"
    private val clock = MutableClock(Instant.parse("2026-10-29T12:00:00Z"))

    /** The seeded credential: expired a minute ago, refreshable. */
    private val expired = McpCredential(
        authToken = "fake-access-0",
        refreshToken = "fake-refresh-0",
        expiresAt = clock.now() - 1.minutes,
        oauthClient = fakeClient,
    )

    private val store = InMemorySecretStore()

    private suspend fun seeded(credential: McpCredential = expired): McpCredentialBinding =
        KeychainMcpCredentialBinding(store).also { it.bind(linkId, mcpUri, credential).getOrThrow() }

    @Test
    fun `a rotated refresh token is persisted before refresh returns`() = runTest {
        val binding = seeded()
        val issuer = RotatingIssuer("fake-refresh-0", clock)

        val refreshed = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, expired).getOrThrow()

        assertEquals("fake-access-1", refreshed.authToken)
        assertEquals("fake-refresh-1", refreshed.refreshToken)
        assertEquals(clock.now() + 86_399.seconds, refreshed.expiresAt)
        assertEquals(fakeClient, refreshed.oauthClient)
        assertEquals(refreshed, binding.resolve(linkId, mcpUri).getOrThrow())
    }

    @Test
    fun `the store holds a live refresh token at every write and is never emptied`() = runTest {
        val binding = seeded()
        val issuer = RotatingIssuer("fake-refresh-0", clock)
        val refresher = McpCredentialRefresher(binding, issuer, clock)

        val first = refresher.refresh(linkId, mcpUri, expired).getOrThrow()
        clock.advance(25.hours)
        refresher.refresh(linkId, mcpUri, first).getOrThrow()

        val writtenRefreshTokens = store.writes.map { (_, secret) -> refreshTokenIn(secret) }
        assertEquals(listOf("fake-refresh-0", "fake-refresh-1", "fake-refresh-2"), writtenRefreshTokens)
        assertTrue(store.deletes.isEmpty(), "a refresh must replace the item, never delete it first")
        assertEquals("fake-refresh-2", issuer.current)
    }

    @Test
    fun `an issuer that does not rotate leaves the stored refresh token in force`() = runTest {
        val binding = seeded()
        val issuer = ScriptedTokenEndpoint { OAuthTokenResult.Issued(accessToken = "fake-access-1", expiresIn = 3600) }

        val refreshed = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, expired).getOrThrow()

        assertEquals("fake-access-1", refreshed.authToken)
        assertEquals("fake-refresh-0", refreshed.refreshToken)
    }

    @Test
    fun `an answer without an expiry stores none`() = runTest {
        val binding = seeded()
        val issuer = ScriptedTokenEndpoint { OAuthTokenResult.Issued(accessToken = "fake-access-1") }

        val refreshed = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, expired).getOrThrow()

        assertNull(refreshed.expiresAt)
    }

    @Test
    fun `a credential another caller already refreshed is used without calling the endpoint`() = runTest {
        val fresh = expired.copy(
            authToken = "fake-access-9",
            refreshToken = "fake-refresh-9",
            expiresAt = clock.now() + 1.hours,
        )
        val binding = seeded(fresh)
        val issuer = RotatingIssuer("fake-refresh-9", clock)

        val result = McpCredentialRefresher(binding, issuer, clock)
            .refresh(linkId, mcpUri, stale = expired)
            .getOrThrow()

        assertEquals(fresh, result)
        assertTrue(issuer.presented.isEmpty())
    }

    @Test
    fun `a token the server refused is refreshed even when its expiry has not come`() = runTest {
        val notDue = expired.copy(expiresAt = clock.now() + 1.hours)
        val binding = seeded(notDue)
        val issuer = RotatingIssuer("fake-refresh-0", clock)

        val result = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, stale = notDue).getOrThrow()

        assertEquals("fake-access-1", result.authToken)
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
    }

    @Test
    fun `a refused refresh token is RefreshRejected and the stored credential is untouched`() = runTest {
        val binding = seeded()
        val issuer = RotatingIssuer("fake-refresh-unrelated", clock)

        val failure = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, expired).exceptionOrNull()

        val rejected = assertIs<McpCredentialException.RefreshRejected>(failure)
        assertEquals("invalid_grant", rejected.error)
        assertEquals(expired, binding.resolve(linkId, mcpUri).getOrThrow())
        assertFalse("fake-refresh-0" in rejected.message.orEmpty())
    }

    @Test
    fun `a refusal because another process rotated first takes the credential that process stored`() = runTest {
        val binding = seeded()
        val theirs = expired.copy(
            authToken = "fake-access-7",
            refreshToken = "fake-refresh-7",
            expiresAt = clock.now() + 1.hours,
        )
        val issuer = ScriptedTokenEndpoint {
            // The other process spent fake-refresh-0 and stored its result a moment before we presented it.
            binding.bind(linkId, mcpUri, theirs).getOrThrow()
            OAuthTokenResult.Rejected("invalid_grant")
        }

        val result = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, expired).getOrThrow()

        assertEquals(theirs, result)
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
    }

    @Test
    fun `an unreachable endpoint is RefreshUnavailable and spends nothing`() = runTest {
        val binding = seeded()
        val issuer = RotatingIssuer("fake-refresh-0", clock).apply {
            outage = OAuthTokenResult.Unavailable("token endpoint answered 503")
        }

        val failure = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, expired).exceptionOrNull()

        assertIs<McpCredentialException.RefreshUnavailable>(failure)
        assertEquals(expired, binding.resolve(linkId, mcpUri).getOrThrow())
    }

    @Test
    fun `a credential with no refresh token is NotRefreshable`() = runTest {
        val bare = McpCredential(authToken = "fake-access-0", expiresAt = clock.now() - 1.minutes)
        val binding = seeded(bare)
        val issuer = RotatingIssuer("fake-refresh-0", clock)

        val failure = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, bare).exceptionOrNull()

        assertIs<McpCredentialException.NotRefreshable>(failure)
        assertTrue(issuer.presented.isEmpty())
    }

    @Test
    fun `nothing bound is NotBound`() = runTest {
        val issuer = RotatingIssuer("fake-refresh-0", clock)

        val failure = McpCredentialRefresher(KeychainMcpCredentialBinding(store), issuer, clock)
            .refresh(linkId, mcpUri, stale = null)
            .exceptionOrNull()

        assertIs<McpCredentialException.NotBound>(failure)
    }

    @Test
    fun `a refreshed credential that cannot be saved is held and saved before the next refresh`() = runTest {
        val binding = seeded()
        val issuer = RotatingIssuer("fake-refresh-0", clock)
        val refresher = McpCredentialRefresher(binding, issuer, clock)

        store.refuseWrites = McpCredentialRefresher.DEFAULT_PERSIST_ATTEMPTS
        val failure = refresher.refresh(linkId, mcpUri, expired).exceptionOrNull()

        assertIs<McpCredentialException.PersistFailed>(failure)
        assertFalse("fake-refresh-1" in failure.message.orEmpty())
        // The issuer has spent fake-refresh-0; the store still holds it, the process holds its successor.
        assertEquals("fake-refresh-1", issuer.current)
        assertEquals("fake-refresh-0", binding.resolve(linkId, mcpUri).getOrThrow()?.refreshToken)

        // The keychain unlocks. The next refresh saves what it held instead of spending anything.
        val recovered = refresher.refresh(linkId, mcpUri, expired).getOrThrow()

        assertEquals("fake-access-1", recovered.authToken)
        assertEquals("fake-refresh-1", binding.resolve(linkId, mcpUri).getOrThrow()?.refreshToken)
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
    }

    @Test
    fun `a save that fails while the store is still locked spends nothing more`() = runTest {
        val binding = seeded()
        val issuer = RotatingIssuer("fake-refresh-0", clock)
        val refresher = McpCredentialRefresher(binding, issuer, clock)

        store.refuseWrites = McpCredentialRefresher.DEFAULT_PERSIST_ATTEMPTS * 2
        refresher.refresh(linkId, mcpUri, expired)
        val second = refresher.refresh(linkId, mcpUri, expired).exceptionOrNull()

        assertIs<McpCredentialException.PersistFailed>(second)
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
    }

    @Test
    fun `a process killed between the rotation and the write recovers through the replay window`() = runTest {
        seeded()
        val issuer = RotatingIssuer("fake-refresh-0", clock)

        // Process one: the issuer answers with fake-refresh-1 and spends fake-refresh-0,
        // and the process dies before the write.
        val doomed = KilledAroundWriteBinding(KeychainMcpCredentialBinding(store), writeFirst = false)
        assertFailsWith<CancellationException> {
            McpCredentialRefresher(doomed, issuer, clock).refresh(linkId, mcpUri, expired)
        }
        assertEquals("fake-refresh-1", issuer.current)
        val survivor = KeychainMcpCredentialBinding(store).resolve(linkId, mcpUri).getOrThrow()
        assertEquals(expired, survivor, "the old credential must still be on disk")

        // Process two, five minutes later: a new binding and refresher over the same keychain.
        clock.advance(5.minutes)
        val binding = KeychainMcpCredentialBinding(store)
        val recovered = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, survivor).getOrThrow()

        assertEquals("fake-access-1", recovered.authToken)
        assertEquals("fake-refresh-1", binding.resolve(linkId, mcpUri).getOrThrow()?.refreshToken)

        // And the chain carries on from the recovered token.
        clock.advance(25.hours)
        val next = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, recovered).getOrThrow()
        assertEquals("fake-refresh-2", next.refreshToken)
    }

    @Test
    fun `a process killed after the write recovers from the store without spending another token`() = runTest {
        seeded()
        val issuer = RotatingIssuer("fake-refresh-0", clock)

        val doomed = KilledAroundWriteBinding(KeychainMcpCredentialBinding(store), writeFirst = true)
        assertFailsWith<CancellationException> {
            McpCredentialRefresher(doomed, issuer, clock).refresh(linkId, mcpUri, expired)
        }

        // Long past the replay window: only the written credential can work now.
        clock.advance(2.hours)
        val binding = KeychainMcpCredentialBinding(store)
        val recovered = McpCredentialRefresher(binding, issuer, clock).refresh(linkId, mcpUri, expired).getOrThrow()

        assertEquals("fake-access-1", recovered.authToken)
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
    }

    @Test
    fun `a stored refresh token replayed after the window is RefreshRejected`() = runTest {
        seeded()
        val issuer = RotatingIssuer("fake-refresh-0", clock)
        val doomed = KilledAroundWriteBinding(KeychainMcpCredentialBinding(store), writeFirst = false)
        assertFailsWith<CancellationException> {
            McpCredentialRefresher(doomed, issuer, clock).refresh(linkId, mcpUri, expired)
        }

        clock.advance(31.minutes)
        val failure = McpCredentialRefresher(KeychainMcpCredentialBinding(store), issuer, clock)
            .refresh(linkId, mcpUri, expired)
            .exceptionOrNull()

        assertIs<McpCredentialException.RefreshRejected>(failure)
    }

    @Test
    fun `isDue asks for a refresh only for a refreshable token near its expiry`() {
        val refresher = McpCredentialRefresher(InMemoryMcpCredentialBinding(), RotatingIssuer("x", clock), clock)

        assertTrue(refresher.isDue(expired))
        assertTrue(refresher.isDue(expired.copy(expiresAt = clock.now() + 4.minutes)))
        assertFalse(refresher.isDue(expired.copy(expiresAt = clock.now() + 1.hours)))
        assertFalse(refresher.isDue(expired.copy(refreshToken = null)))
        assertFalse(refresher.isDue(expired.copy(expiresAt = null)))
    }

    private fun refreshTokenIn(secret: String): String? =
        Json.decodeFromString(McpCredential.serializer(), secret).refreshToken
}
