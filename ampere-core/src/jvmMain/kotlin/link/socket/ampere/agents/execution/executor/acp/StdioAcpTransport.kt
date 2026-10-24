package link.socket.ampere.agents.execution.executor.acp

import co.touchlab.kermit.Logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.execution.process.CancellationAddress
import link.socket.ampere.agents.execution.process.GroupedProcess
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * An [AcpTransport] over a subprocess's stdin and stdout (AMPR-493, AMPR-444 D1).
 *
 * The agent is spawned through [ProcessGroups.start], like every child that could outlive
 * its caller (`docs/concepts/cancellation-address.md`): it leads its own process group,
 * [address] is held from the moment it starts, and [close] is SIGTERM to the group, a bounded
 * wait, then SIGKILL — [GroupedProcess.terminate], never `Process.destroy()`, which on the
 * bash spawn path would end only the wrapper. stdin is closed first, because a well-behaved
 * agent exits on EOF and then nothing has to be signalled.
 *
 * One message per line in both directions. stderr is drained to the logger on a daemon
 * thread, so an agent that logs there cannot fill the pipe and stall.
 *
 * @param command The agent's command line, `["opencode", "acp"]` for the first harness.
 * @param directory The process's working directory. ACP roots a session at `session/new`'s
 *   `cwd` regardless, so this is a courtesy; null inherits the JVM's.
 * @param environment Variables added to the inherited environment.
 */
class StdioAcpTransport(
    private val command: List<String>,
    private val directory: File? = null,
    private val environment: Map<String, String> = emptyMap(),
    private val logger: Logger = Logger.withTag("StdioAcpTransport"),
) : AcpTransport {

    private var grouped: GroupedProcess? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Mutex()

    /** Where to send "stop" for the agent and everything it spawned; null until started or after close. */
    val address: CancellationAddress?
        get() = grouped?.address

    override val isAlive: Boolean
        get() = grouped?.process?.isAlive == true

    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        if (grouped != null) return@withContext Result.success(Unit)
        runCatching {
            val builder = ProcessBuilder(command).redirectErrorStream(false)
            directory?.let { builder.directory(it) }
            builder.environment().putAll(environment)
            val process = ProcessGroups.start(builder)
            grouped = process
            reader = process.process.inputStream.bufferedReader(Charsets.UTF_8)
            writer = process.process.outputStream.bufferedWriter(Charsets.UTF_8)
            thread(name = "acp-stderr-${process.address.leaderPid}", isDaemon = true) {
                runCatching {
                    process.process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        logger.d { "[${command.firstOrNull()}] $line" }
                    }
                }
            }
            Unit
        }
    }

    override suspend fun send(line: String) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                val out = writer ?: throw IOException("the agent process is not started")
                out.write(line)
                out.newLine()
                out.flush()
            }
        }
    }

    override suspend fun receive(): String? = withContext(Dispatchers.IO) {
        val input = reader ?: return@withContext null
        try {
            input.readLine()
        } catch (e: IOException) {
            // The stream was closed under the read, by close() or by the agent exiting.
            null
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            val process = grouped
            runCatching { writer?.close() }
            process?.terminate()
            runCatching { reader?.close() }
            writer = null
            reader = null
            grouped = null
        }
    }
}
