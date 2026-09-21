package link.socket.ampere.agents.tools.mcp.connection

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.execution.process.GroupedProcess
import link.socket.ampere.agents.execution.process.ProcessGroups

/**
 * JVM implementation of StdioProcessHandler using ProcessBuilder.
 *
 * Spawns a child process in its own process group and communicates via stdin/stdout
 * using buffered streams. Stopping terminates the whole group, so servers launched
 * through a wrapper (npx, uvx, shell scripts) don't leave their real process behind.
 */
actual class StdioProcessHandler {
    private var process: GroupedProcess? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    actual suspend fun startProcess(executablePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Spawn the process
            val processBuilder = ProcessBuilder(executablePath)
                .redirectErrorStream(false) // Keep stderr separate for debugging

            val grouped = ProcessGroups.start(processBuilder)
            process = grouped

            // Set up I/O streams
            reader = BufferedReader(InputStreamReader(grouped.process.inputStream))
            writer = BufferedWriter(OutputStreamWriter(grouped.process.outputStream))

            // Verify process started successfully
            if (!grouped.process.isAlive) {
                throw McpConnectionException("Process failed to start: $executablePath")
            }
        }
    }

    actual suspend fun sendMessage(message: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val w = writer ?: throw McpConnectionException("Process not started")

            // Write message with newline delimiter
            w.write(message)
            w.newLine()
            w.flush()
        }
    }

    actual suspend fun receiveMessage(): String = withContext(Dispatchers.IO) {
        val r = reader ?: throw McpConnectionException("Process not started")

        // Read a single line (newline-delimited message)
        r.readLine() ?: throw McpConnectionException("Process terminated or stream closed")
    }

    actual suspend fun stopProcess(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Close streams first
            writer?.close()
            reader?.close()

            // Terminate the process group: SIGTERM, bounded wait, then SIGKILL
            process?.terminate()

            // Clear references
            writer = null
            reader = null
            process = null
        }
    }
}
