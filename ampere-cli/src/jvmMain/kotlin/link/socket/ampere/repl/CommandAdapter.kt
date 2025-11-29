package link.socket.ampere.repl

import com.github.ajalt.clikt.core.CliktCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jline.terminal.Terminal
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Adapts existing CliktCommand instances to run within the REPL.
 *
 * This allows observation commands (watch, status, etc.) to execute
 * in the interactive session and be interrupted via Ctrl+C.
 */
class CommandAdapter(
    private val terminal: Terminal
) {
    /**
     * Execute a CliktCommand in a cancellable context.
     * The command's output is captured and displayed to the terminal.
     */
    suspend fun execute(command: CliktCommand, args: Array<String>): CommandResult {
        return withContext(Dispatchers.IO) {
            try {
                // Capture command output
                val outputStream = ByteArrayOutputStream()
                val printStream = PrintStream(outputStream)

                // Run the command
                command.main(args)

                // Display output to terminal
                terminal.writer().print(outputStream.toString())
                terminal.writer().flush()

                CommandResult.SUCCESS
            } catch (e: Exception) {
                terminal.writer().println("Command failed: ${e.message}")
                CommandResult.ERROR
            }
        }
    }
}
