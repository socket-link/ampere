package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.connection.McpConnectionException
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.McpServerDependency
import link.socket.ampere.time.MutableClock

/**
 * The refresh path in the connection layer (AMPR-464): refresh on connect when the
 * token is due, and once after a 401, with the cached connection replaced each time.
 */
class McpClientCredentialRefreshTest {

    private val dependency = McpServerDependency(name = "work-source", uri = "https://mcp.invalid/mcp")
    private val linkId = LinkId("mcp:work-source")
    private val clock = MutableClock(Instant.parse("2026-10-29T12:00:00Z"))

    private val seed = McpCredential(
        authToken = "fake-access-0",
        refreshToken = "fake-refresh-0",
        expiresAt = clock.now() + 1.hours,
        oauthClient = fakeClient,
    )

    private val store = InMemorySecretStore()
    private val binding = KeychainMcpCredentialBinding(store)
    private val issuer = RotatingIssuer("fake-refresh-0", clock)

    private suspend fun client(
        server: TokenCheckingServer,
        credential: McpCredential = seed,
        refresher: McpCredentialRefresher? = McpCredentialRefresher(binding, issuer, clock),
        factory: (McpServerDependency, McpCredential?) -> McpServerConnection = server.factory,
    ): McpClient {
        binding.bind(linkId, dependency.uri, credential).getOrThrow()
        return McpClient(
            dependency = dependency,
            credentialBinding = binding,
            linkId = linkId,
            connectionFactory = factory,
            credentialRefresher = refresher,
        )
    }

    @Test
    fun `an expired token is refreshed before the connection is built`() = runTest {
        val server = TokenCheckingServer("fake-access-1")
        val client = client(server, credential = seed.copy(expiresAt = clock.now() - 1.minutes))

        client.connect().getOrThrow()

        assertEquals(listOf("fake-access-1"), server.connections.map { it.token })
        assertEquals("fake-refresh-1", binding.resolve(linkId, dependency.uri).getOrThrow()?.refreshToken)
    }

    @Test
    fun `a token inside the refresh margin is refreshed on connect`() = runTest {
        val server = TokenCheckingServer("fake-access-0", "fake-access-1")
        val client = client(server, credential = seed.copy(expiresAt = clock.now() + 2.minutes))

        client.connect().getOrThrow()

        assertEquals(listOf("fake-access-1"), server.connections.map { it.token })
    }

    @Test
    fun `a token that is not due is used as stored`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server)

        client.connect().getOrThrow()

        assertEquals(listOf("fake-access-0"), server.connections.map { it.token })
        assertTrue(issuer.presented.isEmpty())
    }

    @Test
    fun `a 401 mid-session refreshes once and retries once on a new connection`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server)
        client.connect().getOrThrow()

        // The token is revoked before its stated expiry; the refreshed one is good.
        server.accepted.clear()
        server.accepted += "fake-access-1"
        val result = client.callTool("get_issue", null)

        assertTrue(result.isSuccess, "expected the retry to succeed: ${result.exceptionOrNull()}")
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
        val (first, second) = server.connections
        assertEquals("fake-access-0", first.token)
        assertEquals("fake-access-1", second.token)
        assertTrue(first.disconnected, "the connection carrying the refused token is dropped")
        assertEquals(listOf("get_issue"), first.calls)
        assertEquals(listOf("get_issue"), second.calls)
    }

    @Test
    fun `later calls use the refreshed connection without refreshing again`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server)
        client.connect().getOrThrow()
        server.accepted.clear()
        server.accepted += "fake-access-1"
        client.callTool("get_issue", null).getOrThrow()

        client.callTool("get_issue", null).getOrThrow()
        client.listTools().getOrThrow()

        assertEquals(2, server.connections.size)
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
    }

    @Test
    fun `a second 401 after the refresh is a typed failure`() = runTest {
        val server = TokenCheckingServer("fake-access-0").apply { checkHandshake = false }
        val client = client(server)
        client.connect().getOrThrow()

        server.accepted.clear()
        val failure = client.callTool("get_issue", null).exceptionOrNull()

        val rejected = assertIs<McpCredentialException.Rejected>(failure)
        assertTrue(rejected.refreshed)
        assertEquals(linkId, rejected.linkId)
        assertEquals(listOf("fake-refresh-0"), issuer.presented, "refresh once")
        assertEquals(2, server.connections.sumOf { it.calls.size }, "retry once")
    }

    @Test
    fun `a refreshed token the handshake refuses is a typed failure`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server)
        client.connect().getOrThrow()

        server.accepted.clear()
        val failure = client.callTool("get_issue", null).exceptionOrNull()

        assertIs<McpCredentialException.Rejected>(failure)
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
    }

    @Test
    fun `a 401 with no refresher is reported as not refreshable`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server, refresher = null)
        client.connect().getOrThrow()

        server.accepted.clear()
        val failure = client.callTool("get_issue", null).exceptionOrNull()

        val rejected = assertIs<McpCredentialException.Rejected>(failure)
        assertFalse(rejected.refreshed)
        assertEquals(1, server.connections.size)
    }

    @Test
    fun `a refresh that fails is the call's failure and keeps the connection for the next attempt`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server)
        client.connect().getOrThrow()

        server.accepted.clear()
        issuer.outage = OAuthTokenResult.Unavailable("token endpoint answered 503")
        val failure = client.callTool("get_issue", null).exceptionOrNull()

        assertIs<McpCredentialException.RefreshUnavailable>(failure)
        assertEquals(1, server.connections.size)

        // The endpoint comes back; the next call's 401 refreshes and goes through.
        issuer.outage = null
        server.accepted += "fake-access-1"
        assertTrue(client.callTool("get_issue", null).isSuccess)
    }

    @Test
    fun `a 401 on the handshake refreshes and opens once more`() = runTest {
        // Not due by the clock, but revoked: the server refuses the handshake.
        val server = TokenCheckingServer("fake-access-1")
        val client = client(server)

        client.connect().getOrThrow()

        assertEquals(listOf("fake-access-0", "fake-access-1"), server.connections.map { it.token })
        assertTrue(client.isConnected)
    }

    @Test
    fun `a failure other than a 401 is returned as is and refreshes nothing`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val outage = McpConnectionException("HTTP request failed: 503 Service Unavailable")
        val client = client(server, factory = { declared, credential ->
            object : McpServerConnection by server.factory(declared, credential) {
                override suspend fun invokeTool(toolName: String, arguments: JsonElement?): Result<ToolCallResult> =
                    Result.failure(outage)
            }
        })
        client.connect().getOrThrow()

        val failure = client.callTool("get_issue", null).exceptionOrNull()

        assertSame(outage, failure)
        assertTrue(issuer.presented.isEmpty())
    }

    @Test
    fun `a tool dispatched through the connection view gets the same refresh and retry`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server)
        client.connect().getOrThrow()
        val view = assertNotNull(client.activeConnection)

        server.accepted.clear()
        server.accepted += "fake-access-1"
        val result = view.invokeTool("get_issue", null)

        assertTrue(result.isSuccess, "expected the retry to succeed: ${result.exceptionOrNull()}")
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
        assertTrue(view.isConnected)
        assertSame(view, client.activeConnection, "the view outlives the reconnect")
    }
}
