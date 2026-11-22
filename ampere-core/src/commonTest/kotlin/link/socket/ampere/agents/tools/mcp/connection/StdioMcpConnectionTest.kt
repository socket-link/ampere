package link.socket.ampere.agents.tools.mcp.connection

import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.agents.tools.mcp.McpProtocol
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration

/**
 * [StdioMcpConnection] over a scripted pipe (AMPR-515): one stream carries
 * responses, progress, log messages and stray lines, and each has to reach the
 * right place or none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StdioMcpConnectionTest {

    /**
     * A stdio MCP server as a function of what it reads. `tools/call` answers
     * with whatever [beforeAnswer] writes first, then the result unless
     * [answersCalls] is off.
     */
    private class ScriptedPipe : StdioPipe {

        /** Every line the client wrote, parsed. */
        val read = mutableListOf<JsonObject>()

        private val stdout = Channel<String>(Channel.UNLIMITED)

        var answersCalls = true

        /** Lines to write when a `tools/call` arrives, given its id and progress token. */
        var beforeAnswer: (id: String, token: String?) -> List<String> = { _, _ -> emptyList() }

        /** A write of `notifications/cancelled` never returns, as to a process that stopped reading. */
        var blocksOnCancellation = false

        var stopped = false
            private set

        fun methods(): List<String> = read.map { it["method"]!!.jsonPrimitive.content }

        fun idOf(method: String): String = read.last { it["method"]!!.jsonPrimitive.content == method }
            .getValue("id").jsonPrimitive.content

        fun write(line: String) {
            stdout.trySend(line)
        }

        fun answer(id: String, text: String = "called") {
            write("""{"jsonrpc":"2.0","id":"$id","result":{"content":[{"type":"text","text":"$text"}]}}""")
        }

        override suspend fun start(executablePath: String): Result<Unit> = Result.success(Unit)

        override suspend fun send(message: String): Result<Unit> {
            val line = Json.parseToJsonElement(message).jsonObject
            val method = line["method"]!!.jsonPrimitive.content
            if (method == "notifications/cancelled" && blocksOnCancellation) awaitCancellation()
            read += line

            val id = line["id"]?.jsonPrimitive?.content
            when (method) {
                "initialize" -> write(
                    """{"jsonrpc":"2.0","id":"$id","result":{"protocolVersion":"2025-06-18",""" +
                        """"capabilities":{},"serverInfo":{"name":"scripted-stdio","version":"0"}}}""",
                )

                "tools/list" -> write("""{"jsonrpc":"2.0","id":"$id","result":{"tools":[{"name":"echo"}]}}""")

                "tools/call" -> {
                    val token = line["params"]!!.jsonObject["_meta"]?.jsonObject
                        ?.get("progressToken")?.jsonPrimitive?.content
                    beforeAnswer(id!!, token).forEach(::write)
                    if (answersCalls) answer(id)
                }
            }
            return Result.success(Unit)
        }

        override suspend fun receive(): String = stdout.receive()

        override suspend fun stop(): Result<Unit> {
            stopped = true
            stdout.close()
            return Result.success(Unit)
        }
    }

    private val pipe = ScriptedPipe()

    private suspend fun TestScope.connection(): StdioMcpConnection {
        val connection = StdioMcpConnection(
            config = McpServerConfiguration(
                id = "fixture",
                displayName = "Fixture",
                protocol = McpProtocol.STDIO,
                endpoint = "/not/a/real/server",
            ),
            pipe = pipe,
            logger = Logger.withTag("StdioMcpConnectionTest"),
            readerDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        connection.connect().getOrThrow()
        connection.initialize().getOrThrow()
        return connection
    }

    private fun progress(token: String?, progress: Int, message: String): String {
        val tokenMember = if (token == null) "" else """"progressToken":"$token","""
        return """{"jsonrpc":"2.0","method":"notifications/progress",""" +
            """"params":{$tokenMember"progress":$progress,"message":"$message"}}"""
    }

    private val logLine =
        """{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"warning","data":"careful"}}"""

    @Test
    fun `the handshake is completed with the initialized notification`() = runTest {
        val connection = connection()

        assertEquals(listOf("initialize", "notifications/initialized"), pipe.methods())
        assertEquals(listOf("echo"), connection.listTools().getOrThrow().map { it.name })
        connection.disconnect().getOrThrow()
        assertTrue(pipe.stopped)
    }

    @Test
    fun `a notification written before the response is not taken for the response`() = runTest {
        pipe.beforeAnswer = { _, _ -> listOf(logLine) }
        val connection = connection()

        val result = connection.invokeTool("echo", null).getOrThrow()

        assertEquals("called", result.content.single().text)
        connection.disconnect()
    }

    @Test
    fun `progress is routed by token and a log message goes to the call in flight`() = runTest {
        pipe.beforeAnswer = { _, token ->
            listOf(
                progress(token, 1, "mine"),
                progress("someone-elses-token", 50, "not mine"),
                progress(null, 99, "nobody's"),
                logLine,
                progress(token, 2, "mine again"),
            )
        }
        val connection = connection()

        val events = connection.invokeToolStreaming("echo", null).toList()

        assertEquals(4, events.size, "$events")
        assertEquals(McpCallEvent.Progress(progress = 1.0, message = "mine"), events[0])
        assertEquals(McpCallEvent.Log(level = "warning", data = JsonPrimitive("careful")), events[1])
        assertEquals(McpCallEvent.Progress(progress = 2.0, message = "mine again"), events[2])
        assertEquals("called", assertIs<McpCallEvent.Completed>(events[3]).result.content.single().text)
        connection.disconnect()
    }

    @Test
    fun `the plain call asks for no progress`() = runTest {
        var tokenSeen: String? = "unset"
        pipe.beforeAnswer = { _, token ->
            tokenSeen = token
            emptyList()
        }
        val connection = connection()

        connection.invokeTool("echo", null).getOrThrow()

        assertEquals(null, tokenSeen)
        connection.disconnect()
    }

    @Test
    fun `a response to another request and a line that is not a message are passed over`() = runTest {
        pipe.beforeAnswer = { _, _ ->
            listOf(
                """{"jsonrpc":"2.0","id":"a-call-nobody-is-waiting-for","result":{"content":[]}}""",
                "Server listening on stdio",
            )
        }
        val connection = connection()

        val result = connection.invokeTool("echo", null).getOrThrow()

        assertEquals("called", result.content.single().text)
        connection.disconnect()
    }

    @Test
    fun `a call that runs out of time tells the server and its late answer is not taken by the next call`() = runTest {
        pipe.answersCalls = false
        val connection = connection()

        val timedOut = connection.invokeTool("echo", null).exceptionOrNull()
        val abandoned = pipe.idOf("tools/call")

        assertEquals(30_000L, assertIs<McpRequestTimeoutException>(timedOut).timeoutMs)
        val cancelled = pipe.read.last()
        assertEquals("notifications/cancelled", cancelled["method"]!!.jsonPrimitive.content)
        assertEquals(abandoned, cancelled["params"]!!.jsonObject["requestId"]!!.jsonPrimitive.content)

        // The server answers the abandoned call after all, just before the next one's answer.
        pipe.answersCalls = true
        pipe.beforeAnswer = { _, _ -> emptyList<String>().also { pipe.answer(abandoned, text = "too late") } }
        val next = connection.invokeTool("echo", null).getOrThrow()

        assertEquals("called", next.content.single().text)
        connection.disconnect()
    }

    @Test
    fun `a call is given the time limit it asks for`() = runTest {
        pipe.answersCalls = false
        val connection = connection()

        val started = currentTime
        val events = connection.invokeToolStreaming("echo", null, McpCallOptions(timeoutMs = 120_000)).toList()

        val failure = assertIs<McpCallEvent.Failed>(events.single()).cause
        assertEquals(120_000L, assertIs<McpRequestTimeoutException>(failure).timeoutMs)
        assertEquals(120_000L, currentTime - started)
        connection.disconnect()
    }

    @Test
    fun `cancelling the collector writes the cancellation for its request`() = runTest {
        pipe.answersCalls = false
        pipe.beforeAnswer = { _, token -> listOf(progress(token, 1, "started")) }
        val connection = connection()
        val seen = mutableListOf<McpCallEvent>()

        val collecting = launch { connection.invokeToolStreaming("echo", null).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<McpCallEvent>(McpCallEvent.Progress(progress = 1.0, message = "started")), seen)

        collecting.cancelAndJoin()

        val cancelled = pipe.read.last()
        assertEquals("notifications/cancelled", cancelled["method"]!!.jsonPrimitive.content)
        assertEquals(pipe.idOf("tools/call"), cancelled["params"]!!.jsonObject["requestId"]!!.jsonPrimitive.content)
        connection.disconnect()
    }

    @Test
    fun `a server that has stopped reading holds a cancellation for the grace and no longer`() = runTest {
        pipe.answersCalls = false
        pipe.blocksOnCancellation = true
        val connection = connection()

        val collecting = launch { connection.invokeToolStreaming("echo", null).collect { } }
        runCurrent()
        val cancelledAt = currentTime
        collecting.cancelAndJoin()

        assertEquals(StdioMcpConnection.CANCEL_GRACE_MS, currentTime - cancelledAt)
        connection.disconnect()
    }

    @Test
    fun `a process that exits mid call fails the call instead of leaving it waiting`() = runTest {
        pipe.answersCalls = false
        val connection = connection()
        val events = mutableListOf<McpCallEvent>()

        val collecting = launch { connection.invokeToolStreaming("echo", null).collect { events += it } }
        runCurrent()
        connection.disconnect().getOrThrow()
        collecting.join()

        val failure = assertIs<McpCallEvent.Failed>(events.single()).cause
        assertTrue("closed its output" in assertIs<McpConnectionException>(failure).message.orEmpty(), failure.message)
    }
}
