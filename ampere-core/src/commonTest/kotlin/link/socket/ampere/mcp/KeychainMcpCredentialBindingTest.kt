package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.link.CredentialRef
import link.socket.ampere.link.LinkId

class KeychainMcpCredentialBindingTest {

    private val linkId = LinkId("mcp:work-source")
    private val mcpUri = "https://mcp.invalid/mcp"

    private val credential = McpCredential(
        authToken = "fake-access-0",
        refreshToken = "fake-refresh-0",
        expiresAt = Instant.parse("2026-10-30T12:00:00Z"),
        oauthClient = fakeClient,
    )

    @Test
    fun `bind then resolve then unbind round-trips`() = runTest {
        val binding = KeychainMcpCredentialBinding(InMemorySecretStore())

        binding.bind(linkId, mcpUri, credential).getOrThrow()
        assertEquals(credential, binding.resolve(linkId, mcpUri).getOrThrow())

        binding.unbind(linkId, mcpUri).getOrThrow()
        assertNull(binding.resolve(linkId, mcpUri).getOrThrow())
    }

    @Test
    fun `the item is found by the Link alias and the server URI`() = runTest {
        val store = InMemorySecretStore()
        KeychainMcpCredentialBinding(store).bind(linkId, mcpUri, credential).getOrThrow()

        val (service, account) = store.items.keys.single()
        assertEquals("ampere.link:mcp:work-source", service)
        assertEquals(CredentialRef.forLink(linkId).keychainAlias, service)
        assertEquals(mcpUri, account)
    }

    @Test
    fun `the item survives the binding that wrote it`() = runTest {
        val store = InMemorySecretStore()
        KeychainMcpCredentialBinding(store).bind(linkId, mcpUri, credential).getOrThrow()

        // A new process: a new binding over the same keychain.
        assertEquals(credential, KeychainMcpCredentialBinding(store).resolve(linkId, mcpUri).getOrThrow())
    }

    @Test
    fun `two Links never share an item`() = runTest {
        val store = InMemorySecretStore()
        val binding = KeychainMcpCredentialBinding(store)
        val other = LinkId("mcp:other")

        binding.bind(linkId, mcpUri, credential).getOrThrow()
        binding.bind(other, mcpUri, McpCredential(authToken = "fake-other")).getOrThrow()

        assertEquals(credential, binding.resolve(linkId, mcpUri).getOrThrow())
        assertEquals("fake-other", binding.resolve(other, mcpUri).getOrThrow()?.authToken)
        assertEquals(2, store.items.size)
    }

    @Test
    fun `a bind replaces the item in one write and never deletes first`() = runTest {
        val store = InMemorySecretStore()
        val binding = KeychainMcpCredentialBinding(store)

        binding.bind(linkId, mcpUri, credential).getOrThrow()
        binding.bind(linkId, mcpUri, credential.copy(refreshToken = "fake-refresh-1")).getOrThrow()

        assertEquals(2, store.writes.size)
        assertTrue(store.deletes.isEmpty())
        assertEquals("fake-refresh-1", binding.resolve(linkId, mcpUri).getOrThrow()?.refreshToken)
    }

    @Test
    fun `an authToken-only item still decodes`() = runTest {
        val store = InMemorySecretStore()
        store.items[CredentialRef.aliasFor(linkId) to mcpUri] = """{"authToken":"fake-legacy-token"}"""

        val resolved = KeychainMcpCredentialBinding(store).resolve(linkId, mcpUri).getOrThrow()

        assertEquals(McpCredential(authToken = "fake-legacy-token"), resolved)
    }

    @Test
    fun `an item a newer build wrote decodes with its unknown fields skipped`() = runTest {
        val store = InMemorySecretStore()
        store.items[CredentialRef.aliasFor(linkId) to mcpUri] =
            """{"authToken":"fake-access-0","scope":"read","futureField":{"a":1}}"""

        val resolved = KeychainMcpCredentialBinding(store).resolve(linkId, mcpUri).getOrThrow()

        assertEquals("fake-access-0", resolved?.authToken)
    }

    @Test
    fun `an unreadable item fails without quoting it`() = runTest {
        val store = InMemorySecretStore()
        store.items[CredentialRef.aliasFor(linkId) to mcpUri] = """{"authToken":"fake-secret-in-a-broken-item","""

        val failure = KeychainMcpCredentialBinding(store).resolve(linkId, mcpUri).exceptionOrNull()

        assertNotNull(failure)
        val message = failure.message.orEmpty()
        assertFalse("fake-secret-in-a-broken-item" in message, message)
        assertTrue("seed it again" in message, message)
    }

    @Test
    fun `a store failure is the binding failure`() = runTest {
        val store = InMemorySecretStore().apply { refuseWrites = 1 }

        val result = KeychainMcpCredentialBinding(store).bind(linkId, mcpUri, credential)

        assertTrue(result.isFailure)
        assertTrue(store.items.isEmpty())
    }
}
