package link.socket.ampere.repl

import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.terminal.Terminal

/**
 * Handles mode-specific input reading and key mappings for the REPL.
 * Provides different input handling strategies based on the current mode.
 */
class InputHandler(
    private val terminal: Terminal,
    private val reader: LineReader,
    private val modeManager: ModeManager,
    private val executor: CommandExecutor,
    private val filterCycler: EventFilterCycler,
    private val terminalOps: TerminalOperations
) {
    /**
     * Read input based on current mode.
     * @return The command to execute, or null if no command should be executed
     * @throws EndOfFileException when Ctrl+D is pressed
     * @throws EmergencyExitException when double-Esc is pressed
     */
    fun readInput(): String? {
        return when (modeManager.getCurrentMode()) {
            Mode.NORMAL -> readNormalModeInput()
            Mode.INSERT -> readInsertModeInput()
            Mode.OBSERVING -> readObservingModeInput()
        }
    }

    /**
     * Handle Ctrl+D based on current mode.
     */
    fun handleCtrlD() {
        when (modeManager.getCurrentMode()) {
            Mode.OBSERVING -> {
                executor.interrupt()
                modeManager.enterInsertMode()
                terminalOps.println()
                terminalOps.println(TerminalColors.dim("Disconnected from observation"))
            }
            else -> {
                terminalOps.println()
                terminalOps.println("Goodbye! Shutting down environment...")
                kotlin.system.exitProcess(0)
            }
        }
    }

    private fun readNormalModeInput(): String? {
        val key = terminal.reader().read()

        return when (key.toChar()) {
            'w' -> "watch"
            's' -> "status"
            't' -> "thread list"
            'o' -> "outcomes stats"
            'h', '?' -> "help"
            'c' -> "clear"
            'q' -> "exit"
            // Number keys for quick dashboard access
            '1' -> "status"
            '2' -> "thread list"
            '3' -> "outcomes stats"
            '4' -> "watch"
            '5' -> "outcomes ticket"
            '\u000C' -> {  // Ctrl+L
                terminalOps.clearScreen()
                null
            }
            '\u0000' -> {  // Ctrl+Space
                terminalOps.clearScreen()
                null
            }
            'i', 'a' -> {
                modeManager.enterInsertMode()
                null
            }
            '\u001B' -> handleEscapeSequence()
            '\n', '\r' -> {
                // Enter in normal mode = switch to insert
                modeManager.enterInsertMode()
                null
            }
            else -> {
                terminalOps.println(TerminalColors.dim("Unknown key. Press ? for help."))
                null
            }
        }
    }

    private fun handleEscapeSequence(): String? {
        // Peek ahead to see if this is an escape sequence
        if (terminal.reader().peek(100) > 0) {
            val next = terminal.reader().read()
            if (next == '['.code) {
                // This is a CSI sequence, ignore it
                return null
            } else if (next >= '1'.code && next <= '5'.code) {
                // Ctrl+number sequence (Alt+number on some terminals)
                return when (next.toChar()) {
                    '1' -> "status"
                    '2' -> "thread list"
                    '3' -> "outcomes stats"
                    '4' -> "watch"
                    '5' -> "outcomes ticket"
                    else -> null
                }
            }
        }
        // Regular escape handling
        if (modeManager.handleEscape()) {
            throw EmergencyExitException()
        }
        return null
    }

    private fun readInsertModeInput(): String? {
        val line = reader.readLine("")

        // Check for empty enter during observation
        if (line.isBlank() && executor.isExecuting()) {
            executor.interrupt()
            modeManager.enterInsertMode()
            terminalOps.println(TerminalColors.dim("Stopped observation"))
            return null
        }

        return line.trim().takeIf { it.isNotBlank() }
    }

    private fun readObservingModeInput(): String? {
        val key = terminal.reader().read()

        return when (key.toChar()) {
            '\n', '\r' -> {
                // Empty enter stops observation
                executor.interrupt()
                modeManager.enterInsertMode()
                terminalOps.println(TerminalColors.dim("Stopped observation"))
                null
            }
            '\u0005' -> {  // Ctrl+E
                // Cycle filter
                filterCycler.cycle()
                terminalOps.println(
                    TerminalColors.info("Filter: ${filterCycler.currentDisplayName()}")
                )
                null
            }
            else -> null
        }
    }

    /**
     * Get the normal mode key mappings for display purposes.
     */
    fun getNormalModeKeyMappings(): Map<String, String> {
        return mapOf(
            "w" to "watch",
            "s" to "status",
            "t" to "thread list",
            "o" to "outcomes stats",
            "h/?" to "help",
            "c" to "clear",
            "q" to "exit",
            "1" to "status",
            "2" to "thread list",
            "3" to "outcomes stats",
            "4" to "watch",
            "5" to "outcomes ticket",
            "i/a/Enter" to "enter insert mode",
            "Ctrl+L/Ctrl+Space" to "clear screen",
            "Esc Esc" to "emergency exit"
        )
    }
}
