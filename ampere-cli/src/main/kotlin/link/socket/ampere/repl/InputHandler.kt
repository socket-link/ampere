package link.socket.ampere.repl

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader

/**
 * Handles input reading for the REPL.
 * Provides a simple command-line interface without mode switching.
 */
class InputHandler(
    private val reader: LineReader,
    private val executor: CommandExecutor,
    private val terminalOps: TerminalOperations
) {
    /**
     * Read input from the user.
     * @return The command to execute, or null if no command should be executed
     * @throws EndOfFileException when Ctrl+D is pressed
     */
    fun readInput(): String? {
        val line = reader.readLine("")

        // Check for empty enter during command execution
        if (line.isBlank() && executor.isExecuting()) {
            executor.interrupt()
            terminalOps.println(TerminalColors.dim("Command interrupted"))
            return null
        }

        return line.trim().takeIf { it.isNotBlank() }
    }

    /**
     * Handle Ctrl+D - exit the CLI.
     */
    fun handleCtrlD() {
        terminalOps.println()
        terminalOps.println("Goodbye! Shutting down environment...")
        kotlin.system.exitProcess(0)
    }
}
