package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.connection.McpConnectionException
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.connection.McpUnauthorizedException
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.McpCredentialException.ReauthorizationRequired
import link.socket.ampere.plug.McpServerDependency
import link.socket.ampere.time.MutableClock

/**
 * How a credential with no refresh token ends (AMPR-516): typed, as "authorize
 * again", on expiry and on a 401, distinct from a refused refreshable
 * credential and from a transport failure. And how it comes back: the client
 * picks up the credential a new authorization stored.
 */
class McpClientReauthorizationTest {

    private val dependency = McpServerDependency(name = "work-source", uri = FAKE_MCP_URI)
    private val linkId = LinkId("mcp:work-source")
    private val clock = MutableClock(Instant.parse("2026-11-22T12:00:00Z"))
    private val binding = InMemoryMcpCredentialBinding()

    /** What the authorization-code flow stores when the server issues no refresh token. */
    private val granted = McpCredential(
        authToken = "fake-access-1",
        expiresAt = clock.now() + 30.days,
        oauthClient = McpOAuthClient("$FAKE_ISSUER/token", "fake-registered-client-1"),
        grantedScope = McpGrantedScope(setOf("read", "operate"), setOf("read"), statedByServer = true),
    )

    private suspend fun client(
        server: TokenCheckingServer,
        credential: McpCredential = granted,
        refresher: McpCredentialRefresher? = null,
        factory: (McpServerDependency, McpCredential?) -> McpServerConnection = server.factory,
    ): McpClient {
        binding.bind(linkId, dependency.uri, credential).getOrThrow()
        return McpClient(
            dependency = dependency,
            credentialBinding = binding,
            linkId = linkId,
            connectionFactory = factory,
            credentialRefresher = refresher,
            clock = clock,
        )
    }

    private fun refresher() = McpCredentialRefresher(
        binding,
        ScriptedTokenEndpoint { error("a credential with no refresh token is never presented for refresh") },
        clock,
    )

    @Test
    fun `an expired credential with no refresh token needs authorizing again and nothing is sent`() = runTest {
        val server = TokenCheckingServer("fake-access-1")
        val client = client(server)
        clock.advance(30.days + 1.minutes)

        val failure = assertIs<ReauthorizationRequired>(client.connect().exceptionOrNull())

        assertEquals(ReauthorizationRequired.Reason.EXPIRED, failure.reason)
        assertEquals(granted.expiresAt, failure.expiredAt)
        assertEquals(linkId, failure.linkId)
        assertEquals(dependency.uri, failure.mcpUri)
        assertTrue(server.connections.isEmpty(), "no connection is opened on a token known to be dead")
        assertFalse(client.isConnected)
    }

    @Test
    fun `a credential still inside its lifetime connects however close the expiry`() = runTest {
        val server = TokenCheckingServer("fake-access-1")
        val client = client(server)
        clock.advance(30.days - 1.minutes)

        client.connect().getOrThrow()

        assertEquals(listOf("fake-access-1"), server.connections.map { it.token })
    }

    @Test
    fun `a 401 on a credential with no refresh token needs authorizing again`() = runTest {
        val server = TokenCheckingServer("fake-access-1")
        val client = client(server)
        client.connect().getOrThrow()

        server.accepted.clear()
        val failure = assertIs<ReauthorizationRequired>(client.callTool("get_issue", null).exceptionOrNull())

        assertEquals(ReauthorizationRequired.Reason.REFUSED, failure.reason)
        assertIs<McpUnauthorizedException>(failure.cause)
        assertFalse("fake-access-1" in failure.message.orEmpty(), failure.message)
        assertEquals(1, server.connections.size)
    }

    @Test
    fun `it ends the same way with a refresher wired and at the handshake`() = runTest {
        val server = TokenCheckingServer()
        val client = client(server, refresher = refresher())

        val failure = assertIs<ReauthorizationRequired>(client.connect().exceptionOrNull())

        assertEquals(ReauthorizationRequired.Reason.REFUSED, failure.reason)
    }

    @Test
    fun `a hand-seeded bearer with no way to refresh ends the same way`() = runTest {
        val server = TokenCheckingServer("fake-static")
        val client = client(server, credential = McpCredential(authToken = "fake-static"))
        client.connect().getOrThrow()

        server.accepted.clear()

        assertIs<ReauthorizationRequired>(client.listTools().exceptionOrNull())
    }

    @Test
    fun `a refreshable credential that is refused is still Rejected`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val refreshable = McpCredential(
            authToken = "fake-access-0",
            refreshToken = "fake-refresh-0",
            expiresAt = clock.now() + 30.days,
            oauthClient = fakeClient,
        )
        val client = client(server, credential = refreshable)
        client.connect().getOrThrow()

        server.accepted.clear()
        val failure = client.callTool("get_issue", null).exceptionOrNull()

        assertIs<McpCredentialException.Rejected>(failure)
    }

    @Test
    fun `a transport failure is neither`() = runTest {
        val server = TokenCheckingServer("fake-access-1")
        val outage = McpConnectionException("HTTP request failed: 503 Service Unavailable")
        val client = client(
            server,
            factory = { dependency, credential ->
                val real = server.factory(dependency, credential)
                object : McpServerConnection by real {
                    override suspend fun invokeTool(toolName: String, arguments: JsonElement?): Result<ToolCallResult> =
                        Result.failure(outage)
                }
            },
        )
        client.connect().getOrThrow()

        val failure = client.callTool("get_issue", null).exceptionOrNull()

        assertSame(outage, failure)
        assertFalse(failure is McpCredentialException)
    }

    @Test
    fun `after the Link is authorized again the same client reopens on the new credential`() = runTest {
        val server = TokenCheckingServer("fake-access-1")
        val client = client(server)
        client.connect().getOrThrow()

        server.accepted.clear()
        assertIs<ReauthorizationRequired>(client.callTool("get_issue", null).exceptionOrNull())

        // The person authorizes again: the authorizer replaces the stored credential in one bind.
        server.accepted += "fake-access-2"
        binding.bind(linkId, dependency.uri, granted.copy(authToken = "fake-access-2")).getOrThrow()

        val result = client.callTool("get_issue", null)

        assertTrue(result.isSuccess, "expected the new credential to be picked up: ${result.exceptionOrNull()}")
        assertEquals(listOf("fake-access-1", "fake-access-2"), server.connections.map { it.token })
        assertTrue(server.connections.first().disconnected, "the connection carrying the dead token is dropped")
    }

    @Test
    fun `a replacement the server refuses too still needs authorizing again`() = runTest {
        val server = TokenCheckingServer("fake-access-1")
        val client = client(server)
        client.connect().getOrThrow()
        server.accepted.clear()
        binding.bind(linkId, dependency.uri, granted.copy(authToken = "fake-access-2")).getOrThrow()

        val failure = client.callTool("get_issue", null).exceptionOrNull()

        assertIs<ReauthorizationRequired>(failure)
    }

    @Test
    fun `an expired replacement is not opened`() = runTest {
        val server = TokenCheckingServer("fake-access-1", "fake-access-2")
        val client = client(server)
        client.connect().getOrThrow()
        server.accepted -= "fake-access-1"
        binding.bind(
            linkId,
            dependency.uri,
            granted.copy(authToken = "fake-access-2", expiresAt = clock.now() - 1.minutes),
        ).getOrThrow()

        assertIs<ReauthorizationRequired>(client.callTool("get_issue", null).exceptionOrNull())
        assertEquals(1, server.connections.size)
    }

    @Test
    fun `connecting again after the Link is authorized again works on the new credential`() = runTest {
        val server = TokenCheckingServer("fake-access-2")
        val client = client(server)
        clock.advance(31.days)
        assertIs<ReauthorizationRequired>(client.connect().exceptionOrNull())

        binding.bind(
            linkId,
            dependency.uri,
            granted.copy(authToken = "fake-access-2", expiresAt = clock.now() + 30.days),
        ).getOrThrow()

        client.connect().getOrThrow()
        assertEquals(listOf("fake-access-2"), server.connections.map { it.token })
    }
}
