package link.socket.ampere.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.connection.McpCallEvent
import link.socket.ampere.agents.tools.mcp.connection.McpCallOptions
import link.socket.ampere.agents.tools.mcp.connection.McpServerConnection
import link.socket.ampere.agents.tools.mcp.connection.McpUnauthorizedException
import link.socket.ampere.link.LinkId
import link.socket.ampere.plug.McpServerDependency
import link.socket.ampere.time.MutableClock

/**
 * The plug client's credential promises on the streaming path (AMPR-515): a 401
 * is refreshed past once, and only while it is the first thing the call has
 * said. A call that has reported progress was accepted and may have acted, so
 * it is never sent a second time.
 */
class McpClientStreamingTest {

    private val dependency = McpServerDependency(name = "work-source", uri = "https://mcp.invalid/mcp")
    private val linkId = LinkId("mcp:work-source")
    private val clock = MutableClock(Instant.parse("2026-11-22T12:00:00Z"))

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
        factory: (McpServerDependency, McpCredential?) -> McpServerConnection,
        refresher: McpCredentialRefresher? = McpCredentialRefresher(binding, issuer, clock),
    ): McpClient {
        binding.bind(linkId, dependency.uri, seed).getOrThrow()
        return McpClient(
            dependency = dependency,
            credentialBinding = binding,
            linkId = linkId,
            connectionFactory = factory,
            credentialRefresher = refresher,
        )
    }

    /** Revoke the token the client connected with, and accept the one a refresh will mint. */
    private fun TokenCheckingServer.rotate() {
        accepted.remove("fake-access-0")
        accepted += "fake-access-1"
    }

    @Test
    fun `a call the server accepts is streamed as it is`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server.factory)
        client.connect().getOrThrow()

        val events = client.callToolStreaming("get_issue", null).toList()

        assertIs<McpCallEvent.Completed>(events.single())
        assertTrue(issuer.presented.isEmpty())
    }

    @Test
    fun `a 401 before any event is refreshed past and the call made once more`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server.factory)
        client.connect().getOrThrow()
        server.rotate()

        val events = client.callToolStreaming("get_issue", null).toList()

        // The refusal is not an event: the caller sees one call that succeeded.
        assertIs<McpCallEvent.Completed>(events.single())
        assertEquals(listOf("fake-access-0", "fake-access-1"), server.connections.map { it.token })
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
        assertTrue(server.connections[0].disconnected, "the connection that carried the refused token was kept")
    }

    @Test
    fun `a 401 after progress is passed on and the call is not made again`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client({ dep, credential -> RefusedAfterProgress(server.factory(dep, credential)) })
        client.connect().getOrThrow()
        server.rotate()

        val events = client.callToolStreaming("get_issue", null).toList()

        assertEquals(2, events.size, "$events")
        assertIs<McpCallEvent.Progress>(events[0])
        assertIs<McpUnauthorizedException>(assertIs<McpCallEvent.Failed>(events[1]).cause)
        // No refresh, no second connection, no second call: it may already have acted.
        assertTrue(issuer.presented.isEmpty())
        assertEquals(1, server.connections.size)
    }

    @Test
    fun `a second 401 after the refresh is a rejected credential`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        server.checkHandshake = false
        val client = client(server.factory)
        client.connect().getOrThrow()
        // Revoked, and the refreshed token is not accepted either.
        server.accepted.clear()

        val events = client.callToolStreaming("get_issue", null).toList()

        val rejected = assertIs<McpCredentialException.Rejected>(assertIs<McpCallEvent.Failed>(events.single()).cause)
        assertTrue(rejected.refreshed)
    }

    @Test
    fun `with no refresher a 401 is a rejected credential at once`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server.factory, refresher = null)
        client.connect().getOrThrow()
        server.accepted.clear()

        val events = client.callToolStreaming("get_issue", null).toList()

        val rejected = assertIs<McpCredentialException.Rejected>(assertIs<McpCallEvent.Failed>(events.single()).cause)
        assertFalse(rejected.refreshed)
    }

    @Test
    fun `a client that is not connected fails the call as its one event`() = runTest {
        val client = client(TokenCheckingServer("fake-access-0").factory)

        val events = client.callToolStreaming("get_issue", null).toList()

        assertIs<IllegalStateException>(assertIs<McpCallEvent.Failed>(events.single()).cause)
    }

    @Test
    fun `the connection the engine is handed streams with the same promises`() = runTest {
        val server = TokenCheckingServer("fake-access-0")
        val client = client(server.factory)
        client.connect().getOrThrow()
        server.rotate()

        val events = client.activeConnection!!.invokeToolStreaming("get_issue", null).toList()

        assertIs<McpCallEvent.Completed>(events.single())
        assertEquals(listOf("fake-refresh-0"), issuer.presented)
    }

    /** A connection whose streamed call reports progress and is then refused: accepted, then cut off. */
    private class RefusedAfterProgress(
        private val delegate: McpServerConnection,
    ) : McpServerConnection by delegate {

        override fun invokeToolStreaming(
            toolName: String,
            arguments: JsonElement?,
            options: McpCallOptions,
        ): Flow<McpCallEvent> = flow {
            emit(McpCallEvent.Progress(progress = 1.0))
            emit(McpCallEvent.Failed(McpUnauthorizedException("HTTP request failed: 401 Unauthorized")))
        }
    }
}
