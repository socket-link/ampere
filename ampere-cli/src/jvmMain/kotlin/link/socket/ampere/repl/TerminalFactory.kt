package link.socket.ampere.repl

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Factory for creating Mordant Terminal instances with proper configuration
 * for maximum compatibility across terminal emulators.
 *
 * Forces ANSI color support to ensure colors display correctly in:
 * - IntelliJ IDEA terminal
 * - VS Code integrated terminal
 * - iTerm2
 * - macOS Terminal.app
 * - Standard Linux terminals
 */
object TerminalFactory {
    /**
     * Creates a Mordant Terminal with forced ANSI support for color output.
     */
    fun createTerminal(): Terminal {
        return Terminal(
            ansiLevel = AnsiLevel.TRUECOLOR
        )
    }
}
