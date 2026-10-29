package link.socket.ampere

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.InMemoryMcpCredentialBinding
import link.socket.ampere.mcp.McpCredential
import link.socket.ampere.mcp.McpCredentialBinding
import link.socket.ampere.mcp.McpOAuthClient
import link.socket.ampere.mcp.OAuthTokenEndpoint
import link.socket.ampere.mcp.OAuthTokenResult
import org.junit.jupiter.api.Test

/** `ampere reconcile`'s credential choice (AMPR-464): the keychain by default, the env var as an override. */
class WorkSourceCredentialsTest {

    private val uri = "https://mcp.invalid/mcp"
    private val linkId = WorkSourceCredentials.linkIdFor(uri)

    private val seeded = McpCredential(
        authToken = "fake-access-0",
        refreshToken = "fake-refresh-0",
        oauthClient = McpOAuthClient("https://issuer.invalid/token", "fake-client-id"),
    )

    private val noEndpoint = object : OAuthTokenEndpoint {
        override suspend fun refresh(client: McpOAuthClient, refreshToken: String): OAuthTokenResult =
            error("no refresh expected")
    }

    private fun credentials(
        environment: Map<String, String> = emptyMap(),
        keychain: McpCredentialBinding = InMemoryMcpCredentialBinding(),
    ) = WorkSourceCredentials(
        environment = { environment[it] },
        keychain = { keychain },
        tokenEndpoint = { noEndpoint },
    )

    @Test
    fun `the work-source Link is the one minted for its requirement`() {
        assertEquals(LinkId("mcp:work-source"), linkId)
    }

    @Test
    fun `with no env var set the keychain credential is used and refreshed`() = runBlocking<Unit> {
        val keychain = InMemoryMcpCredentialBinding().apply { bind(linkId, uri, seeded).getOrThrow() }

        val opened = credentials(keychain = keychain).open(linkId, uri).getOrThrow()

        assertEquals(WorkSourceCredentials.Origin.KEYCHAIN, opened.origin)
        assertEquals(seeded, opened.binding.resolve(linkId, uri).getOrThrow())
        assertNotNull(opened.refresher)
    }

    @Test
    fun `the env var overrides the keychain as a static token with no refresher`() = runBlocking<Unit> {
        val keychain = InMemoryMcpCredentialBinding().apply { bind(linkId, uri, seeded).getOrThrow() }

        val opened = credentials(
            environment = mapOf(WorkSourceCredentials.TOKEN_ENV to "fake-override-token"),
            keychain = keychain,
        ).open(linkId, uri).getOrThrow()

        assertEquals(WorkSourceCredentials.Origin.ENVIRONMENT_OVERRIDE, opened.origin)
        assertEquals(McpCredential(authToken = "fake-override-token"), opened.binding.resolve(linkId, uri).getOrThrow())
        assertNull(opened.refresher)
        // The override is never written to the keychain.
        assertEquals(seeded, keychain.resolve(linkId, uri).getOrThrow())
    }

    @Test
    fun `a blank env var is no override`() = runBlocking<Unit> {
        val keychain = InMemoryMcpCredentialBinding().apply { bind(linkId, uri, seeded).getOrThrow() }

        val opened = credentials(
            environment = mapOf(WorkSourceCredentials.TOKEN_ENV to "  "),
            keychain = keychain,
        ).open(linkId, uri).getOrThrow()

        assertEquals(WorkSourceCredentials.Origin.KEYCHAIN, opened.origin)
    }

    @Test
    fun `nothing seeded and no override fails with the command that fixes it`() = runBlocking<Unit> {
        val failure = credentials().open(linkId, uri).exceptionOrNull()

        assertIs<WorkSourceCredentialException>(failure)
        val message = failure.message.orEmpty()
        assertTrue("ampere credential seed --work-source $uri" in message, message)
        assertTrue("ampere.link:mcp:work-source" in message, message)
        assertTrue(WorkSourceCredentials.TOKEN_ENV in message, message)
    }

    @Test
    fun `an unreadable keychain fails and points at the override`() = runBlocking<Unit> {
        val broken = object : McpCredentialBinding by InMemoryMcpCredentialBinding() {
            override suspend fun resolve(linkId: LinkId, mcpUri: String): Result<McpCredential?> =
                Result.failure(UnsupportedOperationException("The keychain credential store is macOS-only"))
        }

        val failure = credentials(keychain = broken).open(linkId, uri).exceptionOrNull()

        assertIs<WorkSourceCredentialException>(failure)
        assertTrue("macOS-only" in failure.message.orEmpty())
        assertTrue(WorkSourceCredentials.TOKEN_ENV in failure.message.orEmpty())
    }
}
