package link.socket.ampere.cli.watch

import com.github.ajalt.mordant.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles keyboard input for the interactive watch interface.
 *
 * Uses JLine's NonBlockingReader in raw mode to capture single keystrokes
 * without requiring Enter to be pressed.
 */
class KeyboardInputHandler(
    private val terminal: Terminal
) {
    private val jlineTerminal = TerminalBuilder.terminal()
    private val reader: NonBlockingReader = jlineTerminal.reader()

    init {
        // Enter raw mode for character-by-character input
        jlineTerminal.enterRawMode()
    }

    /**
     * Reads a single character from the terminal in a non-blocking way.
     * Returns null if no input is available.
     */
    suspend fun readKey(): Char? = withContext(Dispatchers.IO) {
        try {
            // Read with 0 timeout (non-blocking)
            val ch = reader.read(1L)  // 1ms timeout
            if (ch != -1 && ch != -2) {  // -1 = EOF, -2 = timeout
                ch.toChar()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Cleanup - restore terminal to normal mode
     */
    fun close() {
        try {
            jlineTerminal.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    /**
     * Process a key press and return the new view configuration if it changed.
     */
    fun processKey(key: Char, current: WatchViewConfig): WatchViewConfig? {
        // If in command mode, handle differently
        if (current.mode == WatchMode.COMMAND) {
            return when {
                key == '\n' || key == '\r' -> {
                    // Execute command (to be implemented)
                    current.copy(mode = WatchMode.DASHBOARD, commandInput = "")
                }
                key == 27.toChar() -> { // ESC
                    current.copy(mode = WatchMode.DASHBOARD, commandInput = "")
                }
                key.code >= 32 -> { // Printable characters
                    current.copy(commandInput = current.commandInput + key)
                }
                key.code == 127 || key.code == 8 -> { // Backspace
                    if (current.commandInput.isNotEmpty()) {
                        current.copy(commandInput = current.commandInput.dropLast(1))
                    } else {
                        current.copy(mode = WatchMode.DASHBOARD, commandInput = "")
                    }
                }
                else -> null
            }
        }

        // Normal mode key handling
        return when (key.lowercaseChar()) {
            'd' -> current.copy(mode = WatchMode.DASHBOARD, showHelp = false)
            'e' -> current.copy(mode = WatchMode.EVENT_STREAM, showHelp = false)
            'm' -> current.copy(mode = WatchMode.MEMORY_OPS, showHelp = false)
            'v' -> current.copy(verboseMode = !current.verboseMode)
            'h', '?' -> current.copy(showHelp = !current.showHelp)
            ':' -> current.copy(mode = WatchMode.COMMAND, commandInput = "")
            27.toChar() -> current.copy(showHelp = false) // ESC closes help
            // Agent focus modes (1-9)
            in '1'..'9' -> {
                // Would need to map numbers to actual agent IDs
                // For now, just switch to agent focus mode
                current.copy(mode = WatchMode.AGENT_FOCUS, showHelp = false)
            }
            'q' -> null // Will be handled specially for quit
            else -> null // No change
        }
    }
}
