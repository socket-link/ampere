package link.socket.ampere.agents.tools.mcp.connection

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.tools.mcp.McpProtocol
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration
import link.socket.ampere.agents.tools.mcp.protocol.McpProtocolException
import link.socket.ampere.agents.tools.mcp.protocol.McpProtocolVersionException

/**
 * [HttpMcpConnection] against a server that refuses what a spec-current
 * Streamable HTTP server refuses (AMPR-514). No network: these run on every
 * platform, so the rules hold wherever the connection is compiled.
 */
class HttpMcpConnectionTest {

    private val server = ScriptedMcpServer()
    private val transport = ScriptedMcpTransport(server)
    private val logLines = mutableListOf<String>()

    private fun connection(authToken: String? = null, timeoutMs: Long = 30_000): HttpMcpConnection {
        val capture = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                logLines += "$message ${throwable?.message.orEmpty()}"
            }
        }
        return HttpMcpConnection(
            config = McpServerConfiguration(
                id = "fixture",
                displayName = "Fixture",
                protocol = McpProtocol.HTTP,
                endpoint = "https://mcp.example.test/mcp",
                authToken = authToken,
                timeoutMs = timeoutMs,
            ),
            httpHandler = transport,
            logger = Logger(loggerConfigInit(capture, minSeverity = Severity.Verbose), "HttpMcpConnectionTest"),
        )
    }

    private suspend fun HttpMcpConnection.open(): HttpMcpConnection {
        connect().getOrThrow()
        initialize().getOrThrow()
        return this
    }

    private fun posts(): List<ScriptedMcpServer.Received> = server.received.filter { it.httpMethod == "POST" }

    @Test
    fun `connect sends nothing`() = runTest {
        val connection = connection()

        connection.connect().getOrThrow()

        assertTrue(connection.isConnected)
        assertTrue(server.received.isEmpty(), "connect() reached the server: ${server.received.map { it.httpMethod }}")
    }

    @Test
    fun `every post accepts both response shapes`() = runTest {
        val connection = connection().open()
        connection.listTools().getOrThrow()
        connection.invokeTool("echo", null).getOrThrow()

        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list", "tools/call"),
            posts().map { it.rpcMethod },
        )
        posts().forEach { post ->
            assertEquals("application/json, text/event-stream", post.headers["accept"], post.rpcMethod)
        }
    }

    @Test
    fun `initialize offers the newest revision and carries no session`() = runTest {
        connection().open()

        val initialize = server.received("initialize").single()
        assertTrue(initialize.body!!.contains("\"protocolVersion\":\"2025-06-18\""), initialize.body)
        assertFalse("mcp-session-id" in initialize.headers)
        assertFalse("mcp-protocol-version" in initialize.headers)
        // An empty capabilities object: this client offers no optional feature.
        assertTrue(initialize.body.contains("\"capabilities\":{}"), initialize.body)
    }

    @Test
    fun `the session id and the negotiated revision ride on every later request`() = runTest {
        val connection = connection().open()
        connection.listTools().getOrThrow()
        connection.invokeTool("echo", null).getOrThrow()

        val later = posts().filter { it.rpcMethod != "initialize" }
        assertEquals(3, later.size)
        later.forEach { post ->
            assertEquals("fixture-session-1", post.headers["mcp-session-id"], post.rpcMethod)
            assertEquals("2025-06-18", post.headers["mcp-protocol-version"], post.rpcMethod)
        }
    }

    @Test
    fun `the handshake is completed with the initialized notification`() = runTest {
        connection().open()

        val notification = server.received("notifications/initialized").single()
        // A notification has no id, and no params member when it has no params.
        assertEquals("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", notification.body)
    }

    @Test
    fun `a request with no params sends none`() = runTest {
        val connection = connection().open()
        connection.listTools().getOrThrow()
        connection.invokeTool("echo", null).getOrThrow()

        assertFalse(server.received("tools/list").single().body!!.contains("params"))
        assertFalse(server.received("tools/call").single().body!!.contains("arguments"))
    }

    @Test
    fun `a JSON answer is decoded`() = runTest {
        server.mode = ScriptedMcpServer.Mode.JSON
        val connection = connection().open()

        val tools = connection.listTools().getOrThrow()
        val called = connection.invokeTool("echo", null).getOrThrow()

        assertEquals(listOf("echo", "undescribed"), tools.map { it.name })
        // A tool may give no description; that is an empty one here, not a failed listing.
        assertEquals("", tools.single { it.name == "undescribed" }.description)
        assertEquals("called echo", called.content.single().text)
    }

    @Test
    fun `an event stream is read until the response arrives`() = runTest {
        server.mode = ScriptedMcpServer.Mode.EVENT_STREAM
        val connection = connection().open()

        val tools = connection.listTools().getOrThrow()
        // The stream carries a comment, an event with no data, a log message and progress for
        // other calls before the response.
        val called = connection.invokeTool("echo", null).getOrThrow()

        assertEquals(listOf("echo", "undescribed"), tools.map { it.name })
        assertEquals("called echo", called.content.single().text)
        assertTrue(
            logLines.any { "notification 'notifications/progress'" in it },
            "progress that belongs to no one was dropped without a trace: $logLines",
        )
    }

    @Test
    fun `a stream that ends before the response is a failure`() = runTest {
        server.mode = ScriptedMcpServer.Mode.EVENT_STREAM
        server.closesStreamBeforeResponse = true
        val connection = connection().open()

        val failure = connection.invokeTool("echo", null).exceptionOrNull()

        val typed = assertIs<McpConnectionException>(failure)
        assertTrue("before answering" in typed.message.orEmpty(), typed.message)
    }

    @Test
    fun `a batched JSON answer from an older server is read`() = runTest {
        server.supportedVersions = listOf("2025-03-26")
        server.batchesResponses = true
        val connection = connection().open()

        assertEquals("called echo", connection.invokeTool("echo", null).getOrThrow().content.single().text)
    }

    @Test
    fun `an error that names no request fails the request it answered`() = runTest {
        server.answersWithoutId = true
        val connection = connection().open()

        val failure = connection.invokeTool("echo", null).exceptionOrNull()

        val typed = assertIs<McpProtocolException>(failure)
        assertTrue("Invalid Request" in typed.message.orEmpty(), typed.message)
    }

    @Test
    fun `a session the server ended is replaced once and the request sent again`() = runTest {
        val connection = connection().open()
        server.endSession()

        val called = connection.invokeTool("echo", null).getOrThrow()

        assertEquals("called echo", called.content.single().text)
        assertEquals(2, server.received("initialize").size)
        val calls = server.received("tools/call")
        assertEquals(listOf("fixture-session-1", "fixture-session-2"), calls.map { it.headers["mcp-session-id"] })
        // The same request, not a second one: a 404 is a refusal, so nothing ran the first time.
        assertEquals(calls[0].rpcId, calls[1].rpcId)

        // And the new session is the one later requests use.
        connection.listTools().getOrThrow()
        assertEquals("fixture-session-2", server.received("tools/list").single().headers["mcp-session-id"])
    }

    @Test
    fun `a session that ends again at once is a typed failure`() = runTest {
        val connection = connection().open()
        server.endsEverySession = true

        val failure = connection.invokeTool("echo", null).exceptionOrNull()

        assertIs<McpSessionExpiredException>(failure)
        // One replacement, not a loop.
        assertEquals(2, server.received("initialize").size)
        assertEquals(2, server.received("tools/call").size)
    }

    @Test
    fun `a server that keeps no session is sent no session`() = runTest {
        server.issuesSessions = false
        val connection = connection().open()
        connection.invokeTool("echo", null).getOrThrow()
        connection.disconnect().getOrThrow()

        assertTrue(posts().none { "mcp-session-id" in it.headers })
        assertTrue(server.received.none { it.httpMethod == "DELETE" }, "there was no session to end")
    }

    @Test
    fun `an older revision is accepted and sends no protocol version header`() = runTest {
        server.supportedVersions = listOf("2025-03-26", "2024-11-05")
        val connection = connection()
        connection.connect().getOrThrow()

        val result = connection.initialize().getOrThrow()
        connection.listTools().getOrThrow()

        assertEquals("2025-03-26", result.protocolVersion)
        // The header arrived with 2025-06-18; an older server was never told to expect it.
        assertTrue(posts().none { "mcp-protocol-version" in it.headers })
    }

    @Test
    fun `a revision this client does not implement fails typed with both revisions named`() = runTest {
        server.forcedVersion = "2099-01-01"
        val connection = connection()
        connection.connect().getOrThrow()

        val failure = connection.initialize().exceptionOrNull()

        val typed = assertIs<McpProtocolVersionException>(failure)
        assertEquals("2025-06-18", typed.offered)
        assertEquals("2099-01-01", typed.answered)
        assertTrue("2025-06-18" in typed.message.orEmpty() && "2099-01-01" in typed.message.orEmpty(), typed.message)
        // The handshake did not complete, so it is not announced as complete.
        assertTrue(server.received("notifications/initialized").isEmpty())
    }

    @Test
    fun `a refused credential is unauthorized and carries the challenge`() = runTest {
        server.requiredBearer = "fake-accepted-token"
        val connection = connection(authToken = "fake-refused-token")
        connection.connect().getOrThrow()

        val failure = connection.initialize().exceptionOrNull()

        val typed = assertIs<McpUnauthorizedException>(failure)
        assertEquals(ScriptedMcpServer.CHALLENGE, typed.wwwAuthenticate)
    }

    @Test
    fun `the bearer token rides on every request`() = runTest {
        server.requiredBearer = "fake-accepted-token"
        val connection = connection(authToken = "fake-accepted-token").open()
        connection.invokeTool("echo", null).getOrThrow()
        connection.disconnect().getOrThrow()

        assertTrue(server.received.all { it.headers["authorization"] == "Bearer fake-accepted-token" })
    }

    @Test
    fun `a status with no meaning of its own is reported with its code`() = runTest {
        val connection = connection().open()
        server.toolCallStatus = 503

        val failure = connection.invokeTool("echo", null).exceptionOrNull()

        val typed = assertIs<McpHttpStatusException>(failure)
        assertEquals(503, typed.status)
        assertTrue("503" in typed.message.orEmpty(), typed.message)
    }

    @Test
    fun `a 404 from a server that keeps no session is not a session ending`() = runTest {
        server.issuesSessions = false
        val connection = connection().open()
        server.toolCallStatus = 404

        val failure = connection.invokeTool("echo", null).exceptionOrNull()

        assertEquals(404, assertIs<McpHttpStatusException>(failure).status)
        assertEquals(1, server.received("initialize").size, "a 404 with no session opened a new one")
    }

    @Test
    fun `disconnect ends the session and closes the transport`() = runTest {
        val connection = connection().open()

        connection.disconnect().getOrThrow()

        val delete = server.received.single { it.httpMethod == "DELETE" }
        assertEquals("fixture-session-1", delete.headers["mcp-session-id"])
        assertEquals("2025-06-18", delete.headers["mcp-protocol-version"])
        assertNull(server.liveSession)
        assertTrue(transport.closed)
        assertFalse(connection.isConnected)
    }

    @Test
    fun `disconnect tolerates a server that does not let a client end its session`() = runTest {
        server.allowsDelete = false
        val connection = connection().open()

        connection.disconnect().getOrThrow()

        assertTrue(transport.closed)
        assertFalse(connection.isConnected)
    }

    @Test
    fun `a request that outlives the configured timeout is a request timeout`() = runTest {
        server.stallsToolCalls = true
        val connection = connection(timeoutMs = 250).open()

        val failure = connection.invokeTool("echo", null).exceptionOrNull()

        assertEquals(250L, assertIs<McpRequestTimeoutException>(failure).timeoutMs)
    }

    @Test
    fun `neither the bearer token nor a whole session id reaches a log line or a request description`() = runTest {
        server.requiredBearer = "fake-accepted-token"
        server.mode = ScriptedMcpServer.Mode.EVENT_STREAM
        val connection = connection(authToken = "fake-accepted-token").open()
        connection.listTools().getOrThrow()
        connection.invokeTool("echo", null).getOrThrow()
        server.endSession()
        connection.invokeTool("echo", null).getOrThrow()
        connection.disconnect().getOrThrow()

        val written = logLines + transport.requests.map { it.toString() }
        assertTrue(written.isNotEmpty())
        written.forEach { line ->
            assertFalse("fake-accepted-token" in line, line)
            assertFalse("fixture-session-1" in line, line)
            assertFalse("fixture-session-2" in line, line)
        }
        // It is still possible to tell from the log that a session exists.
        assertTrue(logLines.any { "session fixt… (17 chars)" in it }, "$logLines")
    }
}
