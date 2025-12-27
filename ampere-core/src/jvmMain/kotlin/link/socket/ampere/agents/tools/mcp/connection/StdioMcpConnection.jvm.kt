package link.socket.ampere.agents.tools.mcp.connection

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JVM implementation of StdioProcessHandler using ProcessBuilder.
 *
 * Spawns a child process and communicates via stdin/stdout using buffered streams.
 */
actual class StdioProcessHandler {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    actual suspend fun startProcess(executablePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Spawn the process
            val processBuilder = ProcessBuilder(executablePath)
                .redirectErrorStream(false) // Keep stderr separate for debugging

            process = processBuilder.start()

            // Set up I/O streams
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))

            // Verify process started successfully
            if (!process!!.isAlive) {
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

            // Terminate the process
            process?.destroy()

            // Wait for termination (with timeout)
            process?.waitFor()

            // Clear references
            writer = null
            reader = null
            process = null
        }
    }
}
