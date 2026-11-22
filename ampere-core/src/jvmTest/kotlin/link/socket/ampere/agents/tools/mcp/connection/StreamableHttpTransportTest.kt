package link.socket.ampere.agents.tools.mcp.connection

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.InMemoryEventApi
import link.socket.ampere.agents.tools.mcp.McpProtocol
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration
import link.socket.ampere.agents.tools.mcp.McpServerManager
import link.socket.ampere.agents.tools.registry.ToolRegistry
import link.socket.ampere.agents.tools.registry.ToolRegistryRepository
import link.socket.ampere.db.Database
import link.socket.ampere.link.LinkId
import link.socket.ampere.mcp.InMemoryMcpCredentialBinding
import link.socket.ampere.mcp.McpClient
import link.socket.ampere.mcp.McpCredential
import link.socket.ampere.mcp.McpCredentialException
import link.socket.ampere.plug.McpServerDependency

/**
 * The Streamable HTTP transport over a socket, through the real OkHttp engine
 * (AMPR-514).
 *
 * [HttpMcpConnectionTest] proves the protocol logic against scripted answers on
 * every platform. This proves what only a real engine can: which headers
 * actually reach a server, that an event stream is read while it is still open,
 * and that the production wiring ([McpServerManager]'s default connection and
 * the plug-facing [McpClient]) reaches a spec-current server with nothing
 * supplied by the host.
 *
 * Bodies use `runBlocking`: the I/O is real, and `runTest`'s virtual clock
 * would run ahead of it.
 */
class StreamableHttpTransportTest {

    private val fixture = StreamableHttpFixture()

    /** Read or script the server under its lock: the fixture's request threads use it too. */
    private fun <T> server(block: ScriptedMcpServer.() -> T): T =
        synchronized(fixture.server) { fixture.server.block() }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    private fun config(authToken: String? = null, timeoutMs: Long = 10_000) = McpServerConfiguration(
        id = "fixture",
        displayName = "Fixture",
        protocol = McpProtocol.HTTP,
        endpoint = fixture.endpoint,
        authToken = authToken,
        timeoutMs = timeoutMs,
    )

    private fun connection(authToken: String? = null, timeoutMs: Long = 10_000): HttpMcpConnection =
        HttpMcpConnection(config(authToken, timeoutMs), HttpClientHandler())

    private suspend fun HttpMcpConnection.open(): HttpMcpConnection {
        connect().getOrThrow()
        initialize().getOrThrow()
        return this
    }

    private fun posts() = server { received.filter { it.httpMethod == "POST" } }

    // --- The fixture is a spec-current server: it refuses what the client before AMPR-514 sent.

    private val raw: HttpClient = HttpClient.newHttpClient()

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        raw.send(request.build(), HttpResponse.BodyHandlers.ofString())

    private fun rawPost(body: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(fixture.endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> request.header(name, value) }
        return send(request)
    }

    @Test
    fun `the fixture refuses the reachability GET the old client opened with`() {
        val response = send(HttpRequest.newBuilder(URI(fixture.endpoint)).GET())

        assertEquals(405, response.statusCode())
        assertEquals("POST, DELETE", response.headers().firstValue("Allow").orElse(null))
    }

    @Test
    fun `the fixture refuses a POST that does not accept both response shapes`() {
        // Content-Type and nothing else: every request the old client sent.
        val response = rawPost(INITIALIZE)

        assertEquals(406, response.statusCode())
    }

    @Test
    fun `the fixture refuses a request that carries neither its session nor its revision`() {
        val accept = "Accept" to "application/json, text/event-stream"
        val handshake = rawPost(INITIALIZE, accept)
        val session = handshake.headers().firstValue("Mcp-Session-Id").orElseThrow()

        // The old client read no response header, so it could send neither of these back.
        val withoutSession = rawPost(TOOLS_LIST, accept)
        val withoutRevision = rawPost(TOOLS_LIST, accept, "Mcp-Session-Id" to session)
        val withBoth = rawPost(
            TOOLS_LIST,
            accept,
            "Mcp-Session-Id" to session,
            "MCP-Protocol-Version" to "2025-06-18",
        )

        assertEquals(200, handshake.statusCode())
        assertEquals(400, withoutSession.statusCode())
        assertEquals(400, withoutRevision.statusCode())
        assertEquals(200, withBoth.statusCode())
    }

    // --- The client, through OkHttp.

    @Test
    fun `the client connects lists and calls when the server answers in JSON`() = runBlocking<Unit> {
        server { mode = ScriptedMcpServer.Mode.JSON }
        val connection = connection().open()

        val tools = connection.listTools().getOrThrow()
        val called = connection.invokeTool("echo", null).getOrThrow()

        assertEquals(listOf("echo", "undescribed"), tools.map { it.name })
        assertEquals("called echo", called.content.single().text)
        assertTrue(server { received.none { it.httpMethod == "GET" } }, "the client probed with a GET")
    }

    @Test
    fun `the client connects lists and calls when the server answers with event streams`() = runBlocking<Unit> {
        server { mode = ScriptedMcpServer.Mode.EVENT_STREAM }
        val connection = connection().open()

        val tools = connection.listTools().getOrThrow()
        val called = connection.invokeTool("echo", null).getOrThrow()

        assertEquals(listOf("echo", "undescribed"), tools.map { it.name })
        assertEquals("called echo", called.content.single().text)
    }

    @Test
    fun `the headers the transport requires are the ones that reach the server`() = runBlocking<Unit> {
        server { requiredBearer = "fake-accepted-token" }
        val connection = connection(authToken = "fake-accepted-token").open()
        connection.listTools().getOrThrow()

        val requests = posts()
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), requests.map { it.rpcMethod })
        requests.forEach { request ->
            // Exactly this: an engine that added `*/*` or dropped one of the two would show here.
            assertEquals("application/json, text/event-stream", request.headers["accept"], request.rpcMethod)
            assertEquals("Bearer fake-accepted-token", request.headers["authorization"], request.rpcMethod)
            assertTrue(request.headers.getValue("content-type").startsWith("application/json"), request.rpcMethod)
        }
        assertNull(requests[0].headers["mcp-session-id"])
        assertNull(requests[0].headers["mcp-protocol-version"])
        requests.drop(1).forEach { request ->
            assertEquals("fixture-session-1", request.headers["mcp-session-id"], request.rpcMethod)
            assertEquals("2025-06-18", request.headers["mcp-protocol-version"], request.rpcMethod)
        }
    }

    @Test
    fun `a call returns when its response arrives though the server keeps the stream open`() = runBlocking<Unit> {
        server { mode = ScriptedMcpServer.Mode.EVENT_STREAM }
        val connection = connection().open()
        server { holdsStreamOpen = true }

        // A client that buffered the body would wait here until the stream closed, which the
        // fixture does not do until the test is over.
        val called = withTimeout(8_000) { connection.invokeTool("echo", null).getOrThrow() }

        assertEquals("called echo", called.content.single().text)
        assertTrue(fixture.holdingStreamOpen.await(5, TimeUnit.SECONDS), "the fixture never held the stream")
        assertTrue(fixture.isHolding, "the stream had already been closed, so this proved nothing")
    }

    @Test
    fun `a session the server ended is replaced and the request answered`() = runBlocking<Unit> {
        val connection = connection().open()
        server { endSession() }

        val called = connection.invokeTool("echo", null).getOrThrow()

        assertEquals("called echo", called.content.single().text)
        assertEquals(
            listOf("fixture-session-1", "fixture-session-2"),
            server { received("tools/call").map { it.headers["mcp-session-id"] } },
        )
    }

    @Test
    fun `a request the server never answers ends at its time limit`() = runBlocking<Unit> {
        val connection = connection(timeoutMs = 500).open()
        server { stallsToolCalls = true }

        val failure = withTimeout(8_000) { connection.invokeTool("echo", null).exceptionOrNull() }

        assertEquals(500L, assertIs<McpRequestTimeoutException>(failure).timeoutMs)
    }

    @Test
    fun `disconnect ends the session with a DELETE`() = runBlocking<Unit> {
        val connection = connection().open()

        connection.disconnect().getOrThrow()

        val delete = server { received.single { it.httpMethod == "DELETE" } }
        assertEquals("fixture-session-1", delete.headers["mcp-session-id"])
        assertNull(server { liveSession })
        assertFalse(connection.isConnected)
    }

    @Test
    fun `disconnect succeeds when the server will not end a session`() = runBlocking<Unit> {
        server { allowsDelete = false }
        val connection = connection().open()

        connection.disconnect().getOrThrow()

        assertFalse(connection.isConnected)
    }

    // --- The production wiring, with nothing supplied by a host.

    @Test
    fun `the server manager reaches the server with its default connection`() = runBlocking<Unit> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val scope = CoroutineScope(Dispatchers.Default)
        val (eventApi, _) = InMemoryEventApi.create(agentId = "test-agent", scope = scope)
        val eventSource = EventSource.Agent(agentId = "test-agent")
        val registry = ToolRegistry(
            repository = ToolRegistryRepository(
                json = Json { ignoreUnknownKeys = true },
                scope = scope,
                database = Database(driver),
            ),
            eventApi = eventApi,
            eventSource = eventSource,
        )
        // No connectionFactory: this is the path a host gets by default.
        val manager = McpServerManager(toolRegistry = registry, eventApi = eventApi, eventSource = eventSource)

        try {
            manager.addServerConfiguration(config()).getOrThrow()
            val discovery = manager.discoverAndRegisterTools().getOrThrow()

            assertNull(discovery.serverResults.getValue("fixture").error)
            assertEquals(2, discovery.totalToolsDiscovered)
            assertTrue(manager.isConnected("fixture"))

            val called = manager.getConnection("fixture")!!.invokeTool("echo", null).getOrThrow()
            assertEquals("called echo", called.content.single().text)
        } finally {
            manager.disconnectAll()
            driver.close()
        }
    }

    @Test
    fun `the plug client reaches the server with its default connection`() = runBlocking<Unit> {
        server { requiredBearer = "fake-accepted-token" }
        val linkId = LinkId("mcp:fixture")
        val binding = InMemoryMcpCredentialBinding()
        binding.bind(linkId, fixture.endpoint, McpCredential(authToken = "fake-accepted-token")).getOrThrow()
        // No connectionFactory: what `PlugContext.create` builds for the work source.
        val client = McpClient(
            dependency = McpServerDependency(name = "fixture", uri = fixture.endpoint),
            credentialBinding = binding,
            linkId = linkId,
        )

        client.connect().getOrThrow()
        val tools = client.listTools().getOrThrow()
        val called = client.callTool("echo", null).getOrThrow()
        client.close().getOrThrow()

        assertEquals(listOf("echo", "undescribed"), tools.map { it.name })
        assertEquals("called echo", called.content.single().text)
        assertTrue(server { received.any { it.httpMethod == "DELETE" } })
    }

    @Test
    fun `a refused credential still reaches the plug client as a 401`() = runBlocking<Unit> {
        server { requiredBearer = "fake-accepted-token" }
        val linkId = LinkId("mcp:fixture")
        val binding = InMemoryMcpCredentialBinding()
        binding.bind(linkId, fixture.endpoint, McpCredential(authToken = "fake-refused-token")).getOrThrow()
        val client = McpClient(
            dependency = McpServerDependency(name = "fixture", uri = fixture.endpoint),
            credentialBinding = binding,
            linkId = linkId,
        )

        val failure = client.connect().exceptionOrNull()

        // A bearer with nothing to refresh it ends in "authorize again" (AMPR-516), and the
        // transport's 401 is its cause: the challenge a host hands to discovery is still there.
        val reauthorize = assertIs<McpCredentialException.ReauthorizationRequired>(failure)
        val refusal = assertIs<McpUnauthorizedException>(reauthorize.cause)
        assertEquals(ScriptedMcpServer.CHALLENGE, refusal.wwwAuthenticate)
    }

    private companion object {

        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"clientInfo":{"name":"raw","version":"0"},"capabilities":{}}}"""

        const val TOOLS_LIST = """{"jsonrpc":"2.0","id":"list-1","method":"tools/list"}"""
    }
}
