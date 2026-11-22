package link.socket.ampere.agents.tools.mcp.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.tools.mcp.McpProtocol
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration

/**
 * A tool call as a stream of events over the Streamable HTTP transport
 * (AMPR-515): progress and log messages in order before one terminal event, a
 * progress token only for a caller that collects, a cancellation the server is
 * told about, and a time limit that is the caller's choice.
 *
 * Proven against [ScriptedMcpServer], not against any real server: the server
 * that motivated the epic answers a waiting call in one JSON body and never
 * streams.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpToolCallStreamingTest {

    private val server = ScriptedMcpServer(mode = ScriptedMcpServer.Mode.EVENT_STREAM)
    private val transport = ScriptedMcpTransport(server)

    private suspend fun connection(): HttpMcpConnection {
        val connection = HttpMcpConnection(
            config = McpServerConfiguration(
                id = "fixture",
                displayName = "Fixture",
                protocol = McpProtocol.HTTP,
                endpoint = "https://mcp.example.test/mcp",
            ),
            httpHandler = transport,
        )
        connection.connect().getOrThrow()
        connection.initialize().getOrThrow()
        return connection
    }

    private fun toolCalls() = server.received("tools/call")

    @Test
    fun `progress and log messages arrive in order before the result`() = runTest {
        val events = connection().invokeToolStreaming("echo", null).toList()

        assertEquals(4, events.size, "$events")
        assertEquals(McpCallEvent.Progress(progress = 1.0, total = 2.0, message = "one of two"), events[0])
        assertEquals(
            McpCallEvent.Log(level = "info", logger = null, data = JsonPrimitive("working on it")),
            events[1],
        )
        assertEquals(McpCallEvent.Progress(progress = 2.0, total = 2.0, message = "two of two"), events[2])
        assertEquals("called echo", assertIs<McpCallEvent.Completed>(events[3]).result.content.single().text)
    }

    @Test
    fun `progress for another token or for none is not attributed to the call`() = runTest {
        val progress = connection().invokeToolStreaming("echo", null).toList()
            .filterIsInstance<McpCallEvent.Progress>()

        // The fixture also sends progress under someone else's token and under no token at all.
        assertEquals(listOf("one of two", "two of two"), progress.map { it.message })
    }

    @Test
    fun `a server that answers in one JSON body yields exactly one terminal event`() = runTest {
        server.mode = ScriptedMcpServer.Mode.JSON

        val events = connection().invokeToolStreaming("echo", null).toList()

        assertEquals("called echo", assertIs<McpCallEvent.Completed>(events.single()).result.content.single().text)
    }

    @Test
    fun `a progress token is sent only when the stream is collected`() = runTest {
        val connection = connection()

        // Building the flow sends nothing.
        val stream = connection.invokeToolStreaming("echo", null)
        assertTrue(toolCalls().isEmpty())

        // The plain call asks for no progress.
        connection.invokeTool("echo", null).getOrThrow()
        assertFalse("_meta" in toolCalls().single().body!!, toolCalls().single().body)

        // Collecting the stream does, under the request's own id.
        stream.toList()
        val streamed = toolCalls().last()
        assertTrue("\"_meta\":{\"progressToken\":\"${streamed.rpcId}\"}" in streamed.body!!, streamed.body)
    }

    @Test
    fun `a failed call is the one terminal event and nothing is thrown`() = runTest {
        server.toolCallStatus = 503

        val events = connection().invokeToolStreaming("echo", null).toList()

        assertEquals(503, assertIs<McpHttpStatusException>(assertIs<McpCallEvent.Failed>(events.single()).cause).status)
    }

    @Test
    fun `cancelling the collector tells the server which request to stop`() = runTest {
        server.goesQuietAfterProgress = true
        val connection = connection()
        val seen = mutableListOf<McpCallEvent>()

        val collecting = launch { connection.invokeToolStreaming("echo", null).collect { seen += it } }
        runCurrent()
        // Mid-stream: the progress has arrived, the answer has not, and the stream is open.
        assertEquals(3, seen.size, "$seen")
        assertTrue(server.cancelledRequestIds().isEmpty())

        collecting.cancelAndJoin()

        assertEquals(listOf(toolCalls().single().rpcId), server.cancelledRequestIds())
        // It went to the session the call was made in, as any other message does.
        val cancellation = server.received("notifications/cancelled").single()
        assertEquals("fixture-session-1", cancellation.headers["mcp-session-id"])
        assertEquals("2025-06-18", cancellation.headers["mcp-protocol-version"])
        assertTrue("\"reason\":\"the caller cancelled the call\"" in cancellation.body!!, cancellation.body)
    }

    @Test
    fun `a collector that has seen enough cancels the call`() = runTest {
        server.goesQuietAfterProgress = true

        // `first` stops collecting once it has its value, which is a cancellation of the call.
        val first = connection().invokeToolStreaming("echo", null).first()

        assertEquals(McpCallEvent.Progress(progress = 1.0, total = 2.0, message = "one of two"), first)
        assertEquals(listOf(toolCalls().single().rpcId), server.cancelledRequestIds())
    }

    @Test
    fun `a collector that leaves at the answer has nothing to cancel`() = runTest {
        val answer = connection().invokeToolStreaming("echo", null).first { it is McpCallEvent.Completed }

        assertIs<McpCallEvent.Completed>(answer)
        assertTrue(server.cancelledRequestIds().isEmpty(), "the server was told to stop a call it had finished")
    }

    @Test
    fun `a server that never takes the cancellation holds the caller for the grace and no longer`() = runTest {
        server.goesQuietAfterProgress = true
        server.stallsCancellations = true
        val connection = connection()

        val collecting = launch { connection.invokeToolStreaming("echo", null).collect { } }
        runCurrent()
        val cancelledAt = currentTime
        collecting.cancelAndJoin()

        assertEquals(HttpMcpConnection.CANCEL_GRACE_MS, currentTime - cancelledAt)
    }

    @Test
    fun `a call outlives the default limit when it is given a longer one`() = runTest {
        server.mode = ScriptedMcpServer.Mode.JSON
        server.toolCallDelayMs = 90_000
        val connection = connection()

        val started = currentTime
        val events = connection.invokeToolStreaming("echo", null, McpCallOptions(timeoutMs = 120_000)).toList()

        assertIs<McpCallEvent.Completed>(events.single())
        assertEquals(90_000L, currentTime - started)
        assertTrue(server.cancelledRequestIds().isEmpty())
    }

    @Test
    fun `the same call fails typed under the default limit and the server is told to stop`() = runTest {
        server.mode = ScriptedMcpServer.Mode.JSON
        server.toolCallDelayMs = 90_000
        val connection = connection()

        val events = connection.invokeToolStreaming("echo", null).toList()

        val failure = assertIs<McpCallEvent.Failed>(events.single()).cause
        assertEquals(30_000L, assertIs<McpRequestTimeoutException>(failure).timeoutMs)
        // A limit that runs out is a cancellation the server has to hear about too.
        assertEquals(listOf(toolCalls().single().rpcId), server.cancelledRequestIds())
    }

    @Test
    fun `the plain call runs under the default limit and tells the server when it runs out`() = runTest {
        server.mode = ScriptedMcpServer.Mode.JSON
        server.toolCallDelayMs = 90_000

        val failure = connection().invokeTool("echo", null).exceptionOrNull()

        assertIs<McpRequestTimeoutException>(failure)
        assertEquals(listOf(toolCalls().single().rpcId), server.cancelledRequestIds())
    }

    @Test
    fun `awaitResult returns the ending of a streamed call`() = runTest {
        val connection = connection()

        val completed = connection.invokeToolStreaming("echo", null).awaitResult()
        server.toolCallStatus = 503
        val failed = connection.invokeToolStreaming("echo", null).awaitResult()

        assertEquals("called echo", completed.getOrThrow().content.single().text)
        assertIs<McpHttpStatusException>(failed.exceptionOrNull())
    }

    @Test
    fun `a session replaced before the first event does not repeat any event`() = runTest {
        val connection = connection()
        server.endSession()

        val events = connection.invokeToolStreaming("echo", null).toList()

        // The 404 came before the server said anything about the call, so the second attempt
        // is the only one that reported.
        assertEquals(4, events.size, "$events")
        assertIs<McpCallEvent.Completed>(events.last())
        assertEquals(2, toolCalls().size)
    }
}
