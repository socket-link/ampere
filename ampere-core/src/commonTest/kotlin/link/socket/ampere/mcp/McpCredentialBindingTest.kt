package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class McpCredentialBindingTest {

    private val linkId = LinkId("link-1")
    private val mcpUri = "mcp://github"

    @Test
    fun `bind then resolve returns the bound credential`() = runTest {
        val binding = InMemoryMcpCredentialBinding()
        val credential = McpCredential(authToken = "abc-123")

        binding.bind(linkId, mcpUri, credential).getOrThrow()
        val resolved = binding.resolve(linkId, mcpUri).getOrThrow()

        assertEquals(credential, resolved)
    }

    @Test
    fun `resolve for unknown linkId or uri returns null`() = runTest {
        val binding = InMemoryMcpCredentialBinding()

        val unknown = binding.resolve(linkId, mcpUri).getOrThrow()

        assertNull(unknown)
    }

    @Test
    fun `unbind removes the credential`() = runTest {
        val binding = InMemoryMcpCredentialBinding()
        val credential = McpCredential(authToken = "abc-123")
        binding.bind(linkId, mcpUri, credential).getOrThrow()

        binding.unbind(linkId, mcpUri).getOrThrow()
        val resolved = binding.resolve(linkId, mcpUri).getOrThrow()

        assertNull(resolved)
    }

    @Test
    fun `credentials scoped per linkId and per uri`() = runTest {
        val binding = InMemoryMcpCredentialBinding()
        val a = McpCredential(authToken = "token-a")
        val b = McpCredential(authToken = "token-b")
        val otherLink = LinkId("link-2")

        binding.bind(linkId, mcpUri, a).getOrThrow()
        binding.bind(otherLink, mcpUri, b).getOrThrow()

        assertEquals(a, binding.resolve(linkId, mcpUri).getOrThrow())
        assertEquals(b, binding.resolve(otherLink, mcpUri).getOrThrow())
        assertNull(binding.resolve(linkId, "mcp://other").getOrThrow())
    }
}
