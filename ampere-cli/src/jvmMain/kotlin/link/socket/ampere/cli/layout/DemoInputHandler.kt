package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader

/**
 * Handles keyboard input for the Jazz Demo.
 *
 * Simplified input handler focused on demo navigation:
 * - Mode switching (d/e/m)
 * - Agent focus (1-9)
 * - Verbose toggle (v)
 * - Help overlay (h/?)
 * - Exit (q/Ctrl+C)
 */
class DemoInputHandler(
    private val terminal: Terminal
) {
    private val jlineTerminal = TerminalBuilder.terminal()
    private val reader: NonBlockingReader = jlineTerminal.reader()

    init {
        jlineTerminal.enterRawMode()
    }

    /**
     * Demo view configuration.
     */
    data class DemoViewConfig(
        val mode: DemoMode = DemoMode.EVENTS,
        val focusedAgentIndex: Int? = null,
        val verboseMode: Boolean = false,
        val showHelp: Boolean = false
    )

    /**
     * Display mode for the demo.
     */
    enum class DemoMode {
        DASHBOARD,
        EVENTS,
        MEMORY,
        AGENT_FOCUS
    }

    /**
     * Result of processing a key.
     */
    sealed class KeyResult {
        data class ConfigChange(val newConfig: DemoViewConfig) : KeyResult()
        object Exit : KeyResult()
        object NoChange : KeyResult()
    }

    /**
     * Read a single key (non-blocking).
     */
    suspend fun readKey(): Char? = withContext(Dispatchers.IO) {
        try {
            val ch = reader.read(1L) // 1ms timeout
            if (ch != -1 && ch != -2) {
                ch.toChar()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Process a key and return the result.
     */
    fun processKey(key: Char, current: DemoViewConfig): KeyResult {
        // Handle Ctrl+C
        if (key.code == 3) {
            return KeyResult.Exit
        }

        // Handle ESC
        if (key.code == 27) {
            return when {
                current.showHelp -> KeyResult.ConfigChange(current.copy(showHelp = false))
                current.mode == DemoMode.AGENT_FOCUS -> KeyResult.ConfigChange(
                    current.copy(mode = DemoMode.DASHBOARD, focusedAgentIndex = null)
                )
                else -> KeyResult.NoChange
            }
        }

        // Handle help overlay - any key dismisses
        if (current.showHelp && key.code != 27) {
            return KeyResult.ConfigChange(current.copy(showHelp = false))
        }

        // Normal key handling
        return when (key.lowercaseChar()) {
            'd' -> KeyResult.ConfigChange(current.copy(mode = DemoMode.DASHBOARD, focusedAgentIndex = null, showHelp = false))
            'e' -> KeyResult.ConfigChange(current.copy(mode = DemoMode.EVENTS, focusedAgentIndex = null, showHelp = false))
            'm' -> KeyResult.ConfigChange(current.copy(mode = DemoMode.MEMORY, focusedAgentIndex = null, showHelp = false))
            'v' -> KeyResult.ConfigChange(current.copy(verboseMode = !current.verboseMode))
            'h', '?' -> KeyResult.ConfigChange(current.copy(showHelp = true))
            'q' -> KeyResult.Exit

            // Number keys for agent focus mode
            in '1'..'9' -> {
                val index = key - '0'
                KeyResult.ConfigChange(current.copy(mode = DemoMode.AGENT_FOCUS, focusedAgentIndex = index))
            }

            else -> KeyResult.NoChange
        }
    }

    /**
     * Cleanup - restore terminal.
     */
    fun close() {
        try {
            jlineTerminal.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}
