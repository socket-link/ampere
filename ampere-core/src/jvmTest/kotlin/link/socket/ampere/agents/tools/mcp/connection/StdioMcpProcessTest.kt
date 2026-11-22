package link.socket.ampere.agents.tools.mcp.connection

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.agents.tools.mcp.McpProtocol
import link.socket.ampere.agents.tools.mcp.McpServerConfiguration

/**
 * [StdioMcpConnection] against a real child process (AMPR-515).
 *
 * [StdioMcpConnectionTest] proves the routing against a scripted pipe. This
 * proves what only a process can: that a call is cancelled and timed out while
 * the read of the child's stdout is blocked in the kernel, where a coroutine's
 * cancellation does not reach, and that the cancellation really lands on the
 * child's stdin.
 *
 * The server is a `sh` script with canned replies. It appends every line it
 * reads to a log, which is how the test sees what the client wrote.
 */
class StdioMcpProcessTest {

    private val directory: Path = Files.createTempDirectory("stdio-mcp-process-test")
    private val log: Path = directory.resolve("server.log")
    private val connections = mutableListOf<StdioMcpConnection>()

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { it.disconnect() } }
        directory.toFile().deleteRecursively()
    }

    private fun logLines(): List<String> = if (Files.exists(log)) Files.readAllLines(log) else emptyList()

    /**
     * The server. `echo` reports progress under the caller's token, logs, and
     * answers; `slow` reports progress and never answers. A request's id is cut
     * out of the line with `sed`, relying on the client's fixed field order.
     */
    private fun server(): Path {
        val d = "$"
        val body = """
            |#!/bin/sh
            |LOG="$log"
            |while IFS= read -r line; do
            |  printf '%s\n' "${d}line" >> "${d}LOG"
            |  id=$d(printf '%s' "${d}line" | sed -n 's/^{"jsonrpc":"2.0","id":"\([^"]*\)".*/\1/p')
            |  case "${d}line" in
            |    *'"method":"initialize"'*)
            |      printf '{"jsonrpc":"2.0","id":"%s","result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"fake-stdio","version":"0"}}}\n' "${d}id" ;;
            |    *'"method":"tools/list"'*)
            |      printf '{"jsonrpc":"2.0","id":"%s","result":{"tools":[{"name":"echo"},{"name":"slow"}]}}\n' "${d}id" ;;
            |    *'"method":"tools/call"'*'"name":"slow"'*)
            |      printf '{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"%s","progress":1,"message":"started"}}\n' "${d}id" ;;
            |    *'"method":"tools/call"'*)
            |      printf '{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"%s","progress":1,"total":1,"message":"done"}}\n' "${d}id"
            |      printf '{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info","data":"from the child"}}\n'
            |      printf '{"jsonrpc":"2.0","id":"%s","result":{"content":[{"type":"text","text":"called"}]}}\n' "${d}id" ;;
            |  esac
            |done
            |
        """.trimMargin()
        val script = directory.resolve("fake-stdio-mcp-server.sh")
        Files.writeString(script, body)
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    private suspend fun connection(timeoutMs: Long = 10_000): StdioMcpConnection {
        val connection = StdioMcpConnection(
            config = McpServerConfiguration(
                id = "fake-stdio",
                displayName = "Fake stdio server",
                protocol = McpProtocol.STDIO,
                endpoint = server().toString(),
                timeoutMs = timeoutMs,
            ),
            processHandler = StdioProcessHandler(),
        )
        connections += connection
        connection.connect().getOrThrow()
        connection.initialize().getOrThrow()
        return connection
    }

    /** Wait, briefly, for the child to have read a line that contains [text]. */
    private suspend fun awaitLogged(text: String): String = withTimeout(5_000) {
        var found = logLines().firstOrNull { text in it }
        while (found == null) {
            delay(20)
            found = logLines().firstOrNull { text in it }
        }
        found
    }

    @Test
    fun `a real process is initialized listed and called with its progress streamed`() = runBlocking<Unit> {
        val connection = connection()

        val tools = connection.listTools().getOrThrow()
        val events = connection.invokeToolStreaming("echo", null).toList()

        assertEquals(listOf("echo", "slow"), tools.map { it.name })
        assertEquals(3, events.size, "$events")
        assertEquals(McpCallEvent.Progress(progress = 1.0, total = 1.0, message = "done"), events[0])
        assertEquals("info", assertIs<McpCallEvent.Log>(events[1]).level)
        assertEquals("called", assertIs<McpCallEvent.Completed>(events[2]).result.content.single().text)
        // The handshake was completed, which a server is entitled to wait for.
        awaitLogged("\"method\":\"notifications/initialized\"")
    }

    @Test
    fun `cancelling a call the process has not answered writes the cancellation to it`() = runBlocking<Unit> {
        val connection = connection()

        // `first` takes the progress and stops collecting, with the answer never coming.
        val first = withTimeout(8_000) { connection.invokeToolStreaming("slow", null).first() }

        assertEquals(McpCallEvent.Progress(progress = 1.0, message = "started"), first)
        val call = awaitLogged("\"name\":\"slow\"")
        val cancellation = awaitLogged("\"method\":\"notifications/cancelled\"")
        val callId = Regex("\"id\":\"([^\"]+)\"").find(call)!!.groupValues[1]
        assertTrue("\"requestId\":\"$callId\"" in cancellation, cancellation)
    }

    @Test
    fun `a collector cancelled while the process is silent is let go`() = runBlocking<Unit> {
        val connection = connection()

        val collecting = launch { connection.invokeToolStreaming("slow", null).collect { } }
        awaitLogged("\"name\":\"slow\"")
        // The read of the child's stdout is blocked in the kernel; the caller is not.
        withTimeout(8_000) { collecting.cancelAndJoin() }

        awaitLogged("\"method\":\"notifications/cancelled\"")
    }

    @Test
    fun `a call the process never answers ends at its time limit and the next call still works`() =
        runBlocking<Unit> {
            val connection = connection()

            val events = connection.invokeToolStreaming("slow", null, McpCallOptions(timeoutMs = 400)).toList()

            val failure = assertIs<McpCallEvent.Failed>(events.last()).cause
            assertEquals(400L, assertIs<McpRequestTimeoutException>(failure).timeoutMs)
            awaitLogged("\"method\":\"notifications/cancelled\"")

            // The connection is not left waiting on the call it gave up on.
            val next = connection.invokeTool("echo", null).getOrThrow()
            assertEquals("called", next.content.single().text)
        }
}
