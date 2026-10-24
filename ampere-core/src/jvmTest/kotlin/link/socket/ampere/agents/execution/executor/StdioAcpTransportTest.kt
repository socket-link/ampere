package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.execution.executor.acp.StdioAcpTransport
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * [StdioAcpTransport] against a real `sh` agent (AMPR-493): one line each way, stderr drained,
 * EOF when the agent exits, and `close()` ending the whole process group.
 *
 * `runBlocking`: these are real processes on real pipes.
 */
class StdioAcpTransportTest {

    private val posix = File.separatorChar == '/'
    private val directory: Path = Files.createTempDirectory("acp-transport-test")
    private val scripts = FakeAcpAgentScript(directory)
    private val spawned = mutableListOf<CancellationAddress>()

    @AfterTest
    fun reapStragglers() {
        spawned.forEach { ProcessGroups.terminate(it, grace = 200.milliseconds) }
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `a line reaches the agent and its answer comes back as one line`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val transport = start(scripts.agent())

        transport.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}""")
        val reply = transport.receive()

        assertNotNull(reply)
        assertTrue(reply.contains("\"protocolVersion\":1"), reply)
        assertTrue(reply.contains("\"id\":1"), "the id came back: $reply")
        transport.close()
    }

    @Test
    fun `an agent that writes to stderr on every line does not block the exchange`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val transport = start(scripts.agent(stderrNoise = true))

        repeat(50) { index ->
            transport.send(
                """{"jsonrpc":"2.0","id":$index,"method":"session/new","params":{"cwd":"/tmp","mcpServers":[]}}""",
            )
            assertTrue(transport.receive()!!.contains("sess-1"))
        }
        transport.close()
    }

    @Test
    fun `the agent exiting yields null from receive and the transport is no longer alive`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val transport = start(scripts.exiting(code = 3))

        transport.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        val reply = transport.receive()

        assertNull(reply, "EOF, not a line")
        waitUntil(5.seconds) { !transport.isAlive }
        transport.close()
    }

    @Test
    fun `close ends the agent and everything it started`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val childPid = directory.resolve("child.pid")
        val transport = start(scripts.agent(preamble = "sleep 600 & echo $! > \"$childPid\""))
        transport.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        assertNotNull(transport.receive())
        waitUntil(5.seconds) { Files.exists(childPid) && Files.readString(childPid).isNotBlank() }
        val child = Files.readString(childPid).trim().toLong()
        val address = assertNotNull(transport.address)

        transport.close()

        assertFalse(ProcessGroups.isAlive(address), "nothing is left at the address")
        waitUntil(5.seconds) { ProcessHandle.of(child).map { !it.isAlive }.orElse(true) }
        assertNull(transport.address)
        assertFalse(transport.isAlive)
    }

    @Test
    fun `a command that cannot be spawned fails start rather than throwing`() = runBlocking<Unit> {
        if (!posix) return@runBlocking
        val transport = StdioAcpTransport(listOf(directory.resolve("no-such-agent").toString()))

        val started = transport.start()
        // On the bash spawn path the wrapper starts and then fails to exec, so start may
        // succeed and the first receive see EOF; either way nothing hangs and nothing throws.
        started.getOrNull()?.let {
            transport.address?.let { spawned += it }
            assertNull(transport.receive())
        }
        transport.close()
        assertEquals(null, transport.address)
    }

    private suspend fun start(script: File): StdioAcpTransport {
        val transport = StdioAcpTransport(listOf(script.absolutePath))
        transport.start().getOrThrow()
        spawned += assertNotNull(transport.address)
        return transport
    }

    private fun waitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within $timeout" }
            Thread.sleep(25)
        }
    }
}
