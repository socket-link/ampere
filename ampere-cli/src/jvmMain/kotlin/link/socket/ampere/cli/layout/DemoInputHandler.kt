package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader

/**
 * Handles keyboard input for the Jazz Demo.
 *
 * Hierarchical vim-like navigation:
 * - `a` then `1-9` -> Select agent
 * - `e` then `1-9` -> Expand event details
 * - `d` -> Dashboard mode
 * - `m` -> Memory mode
 * - `v` -> Toggle verbose
 * - `h`/`?` -> Help
 * - `q`/Ctrl+C -> Exit
 * - ESC -> Cancel pending input or return from focus
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
     * Input mode for hierarchical navigation.
     */
    enum class InputMode {
        NORMAL,              // Waiting for first key
        AWAITING_AGENT,      // Pressed 'a', waiting for 1-9
        AWAITING_EVENT       // Pressed 'e' in events mode, waiting for 1-9
    }

    /**
     * Demo view configuration.
     */
    data class DemoViewConfig(
        val mode: DemoMode = DemoMode.EVENTS,
        val focusedAgentIndex: Int? = null,
        val expandedEventIndex: Int? = null,
        val verboseMode: Boolean = false,
        val showHelp: Boolean = false,
        val inputMode: InputMode = InputMode.NORMAL,
        val inputHint: String? = null  // Shown in status bar
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
        // Handle Ctrl+C - always exits
        if (key.code == 3) {
            return KeyResult.Exit
        }

        // Handle ESC
        if (key.code == 27) {
            return when {
                // Cancel pending input mode first
                current.inputMode != InputMode.NORMAL -> KeyResult.ConfigChange(
                    current.copy(inputMode = InputMode.NORMAL, inputHint = null)
                )
                current.showHelp -> KeyResult.ConfigChange(current.copy(showHelp = false))
                current.expandedEventIndex != null -> KeyResult.ConfigChange(
                    current.copy(expandedEventIndex = null)
                )
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

        // Handle pending input modes
        when (current.inputMode) {
            InputMode.AWAITING_AGENT -> {
                return when (key) {
                    in '1'..'9' -> {
                        val index = key - '0'
                        KeyResult.ConfigChange(
                            current.copy(
                                mode = DemoMode.AGENT_FOCUS,
                                focusedAgentIndex = index,
                                inputMode = InputMode.NORMAL,
                                inputHint = null
                            )
                        )
                    }
                    else -> {
                        // Cancel and process as normal key
                        val reset = current.copy(inputMode = InputMode.NORMAL, inputHint = null)
                        processNormalKey(key, reset)
                    }
                }
            }
            InputMode.AWAITING_EVENT -> {
                return when (key) {
                    in '1'..'9' -> {
                        val index = key - '0'
                        KeyResult.ConfigChange(
                            current.copy(
                                expandedEventIndex = index,
                                inputMode = InputMode.NORMAL,
                                inputHint = null
                            )
                        )
                    }
                    else -> {
                        // Cancel and process as normal key
                        val reset = current.copy(inputMode = InputMode.NORMAL, inputHint = null)
                        processNormalKey(key, reset)
                    }
                }
            }
            InputMode.NORMAL -> {
                return processNormalKey(key, current)
            }
        }
    }

    private fun processNormalKey(key: Char, current: DemoViewConfig): KeyResult {
        return when (key.lowercaseChar()) {
            // 'a' enters agent selection mode
            'a' -> KeyResult.ConfigChange(
                current.copy(
                    inputMode = InputMode.AWAITING_AGENT,
                    inputHint = "agent [1-9]"
                )
            )

            // 'd' goes to dashboard
            'd' -> KeyResult.ConfigChange(
                current.copy(
                    mode = DemoMode.DASHBOARD,
                    focusedAgentIndex = null,
                    expandedEventIndex = null,
                    showHelp = false
                )
            )

            // 'e' - if already in events mode, enter event selection; otherwise switch to events
            'e' -> {
                if (current.mode == DemoMode.EVENTS) {
                    KeyResult.ConfigChange(
                        current.copy(
                            inputMode = InputMode.AWAITING_EVENT,
                            inputHint = "event [1-9]"
                        )
                    )
                } else {
                    KeyResult.ConfigChange(
                        current.copy(
                            mode = DemoMode.EVENTS,
                            focusedAgentIndex = null,
                            expandedEventIndex = null,
                            showHelp = false
                        )
                    )
                }
            }

            'm' -> KeyResult.ConfigChange(
                current.copy(
                    mode = DemoMode.MEMORY,
                    focusedAgentIndex = null,
                    expandedEventIndex = null,
                    showHelp = false
                )
            )
            'v' -> KeyResult.ConfigChange(current.copy(verboseMode = !current.verboseMode))
            'h', '?' -> KeyResult.ConfigChange(current.copy(showHelp = true))
            'q' -> KeyResult.Exit

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
