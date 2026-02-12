package link.socket.ampere.repl

import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp

/**
 * Utility class for common terminal operations.
 */
class TerminalOperations(
    private val terminal: Terminal
) {
    /**
     * Clear the terminal screen.
     */
    fun clearScreen() {
        terminal.puts(InfoCmp.Capability.clear_screen)
        terminal.flush()
    }

    /**
     * Print a line to the terminal.
     */
    fun println(message: String = "") {
        terminal.writer().println(message)
    }

    /**
     * Print without newline.
     */
    fun print(message: String) {
        terminal.writer().print(message)
    }

    /**
     * Get terminal width.
     */
    fun getWidth(): Int = terminal.width

    /**
     * Get terminal height.
     */
    fun getHeight(): Int = terminal.height
}
