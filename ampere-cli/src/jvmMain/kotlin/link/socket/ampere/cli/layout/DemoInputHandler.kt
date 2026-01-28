package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jline.terminal.TerminalBuilder
import org.jline.utils.NonBlockingReader

/**
 * Handles keyboard input for the interactive TUI.
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
 *
 * Escalation response (when awaiting human input):
 * - `a`/`A`/`1` -> Select Option A
 * - `b`/`B`/`2` -> Select Option B
 * - ESC -> Skip with default response (A)
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
        AWAITING_EVENT,      // Pressed 'e' in events mode, waiting for 1-9
        COMMAND,             // In command mode, typing a command
        AWAITING_ESCALATION  // Waiting for A/B response to escalation prompt
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
        val inputHint: String? = null,  // Shown in status bar
        val commandInput: String = "",  // Current command being typed
        val escalationRequestId: String? = null  // Active escalation request ID for human response
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
        data class ExecuteCommand(val command: String, val newConfig: DemoViewConfig) : KeyResult()
        /**
         * Escalation response from human input (A/B selection).
         * @param requestId The escalation request ID to respond to
         * @param response The human's response (e.g., "A" or "B")
         * @param newConfig Updated config with escalation cleared
         */
        data class EscalationResponse(
            val requestId: String,
            val response: String,
            val newConfig: DemoViewConfig
        ) : KeyResult()
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
                // Escalation mode: ESC provides default response "A"
                current.inputMode == InputMode.AWAITING_ESCALATION && current.escalationRequestId != null -> {
                    KeyResult.EscalationResponse(
                        requestId = current.escalationRequestId,
                        response = "A",
                        newConfig = current.copy(
                            inputMode = InputMode.NORMAL,
                            inputHint = null,
                            escalationRequestId = null
                        )
                    )
                }
                // Cancel command mode
                current.inputMode == InputMode.COMMAND -> KeyResult.ConfigChange(
                    current.copy(inputMode = InputMode.NORMAL, inputHint = null, commandInput = "")
                )
                // Cancel other pending input modes
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
            InputMode.COMMAND -> {
                return processCommandKey(key, current)
            }
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
            InputMode.AWAITING_ESCALATION -> {
                return processEscalationKey(key, current)
            }
            InputMode.NORMAL -> {
                return processNormalKey(key, current)
            }
        }
    }

    /**
     * Process a key when awaiting escalation response.
     *
     * Maps:
     * - 'a'/'A' or '1' -> Option A response
     * - 'b'/'B' or '2' -> Option B response
     * - Other keys are ignored (escalation takes priority)
     */
    private fun processEscalationKey(key: Char, current: DemoViewConfig): KeyResult {
        val requestId = current.escalationRequestId ?: return KeyResult.NoChange

        val response = when (key) {
            'a', 'A', '1' -> "A"
            'b', 'B', '2' -> "B"
            else -> return KeyResult.NoChange
        }

        return KeyResult.EscalationResponse(
            requestId = requestId,
            response = response,
            newConfig = current.copy(
                inputMode = InputMode.NORMAL,
                inputHint = null,
                escalationRequestId = null
            )
        )
    }

    /**
     * Process a key in command mode.
     */
    private fun processCommandKey(key: Char, current: DemoViewConfig): KeyResult {
        return when {
            // Enter: execute command
            key == '\n' || key == '\r' -> {
                if (current.commandInput.isNotEmpty()) {
                    KeyResult.ExecuteCommand(
                        command = current.commandInput,
                        newConfig = current.copy(
                            inputMode = InputMode.NORMAL,
                            inputHint = null,
                            commandInput = ""
                        )
                    )
                } else {
                    // Empty command - just exit command mode
                    KeyResult.ConfigChange(
                        current.copy(
                            inputMode = InputMode.NORMAL,
                            inputHint = null,
                            commandInput = ""
                        )
                    )
                }
            }
            // Backspace: delete last character
            key.code == 127 || key.code == 8 -> {
                KeyResult.ConfigChange(
                    current.copy(
                        commandInput = current.commandInput.dropLast(1),
                        inputHint = ":${current.commandInput.dropLast(1)}"
                    )
                )
            }
            // Printable character: append to command
            key.code >= 32 -> {
                val newCommand = current.commandInput + key
                KeyResult.ConfigChange(
                    current.copy(
                        commandInput = newCommand,
                        inputHint = ":$newCommand"
                    )
                )
            }
            else -> KeyResult.NoChange
        }
    }

    private fun processNormalKey(key: Char, current: DemoViewConfig): KeyResult {
        return when (key) {
            // ':' enters command mode
            ':' -> KeyResult.ConfigChange(
                current.copy(
                    inputMode = InputMode.COMMAND,
                    inputHint = ":",
                    commandInput = ""
                )
            )

            // 'a' enters agent selection mode
            'a', 'A' -> KeyResult.ConfigChange(
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
