package link.socket.ampere.repl

import org.jline.terminal.Terminal

/**
 * Renders the status bar showing current mode, filter, and helpful hints.
 *
 * This provides contextual awareness similar to vim's status line,
 * showing what mode you're in and what actions are available.
 */
class StatusBar(
    private val terminal: Terminal
) {
    private var currentMode: Mode = Mode.INSERT
    private var currentFilter: String? = null
    private var customMessage: String? = null

    fun render(mode: Mode, filter: String? = null, message: String? = null) {
        currentMode = mode
        currentFilter = filter
        customMessage = message

        val width = terminal.width
        val separator = "─".repeat(width.coerceAtLeast(1))

        terminal.writer().println(separator)

        // Render mode and status line
        val statusLine = buildStatusLine()
        terminal.writer().println(statusLine)

        // Render prompt based on mode
        val prompt = when (mode) {
            Mode.NORMAL -> TerminalColors.highlight("⚡") + " "
            Mode.INSERT -> TerminalColors.highlight("⚡") + " "
            Mode.OBSERVING -> ""  // No prompt during observation
        }

        terminal.writer().print(prompt)
        terminal.writer().flush()
    }

    private fun buildStatusLine(): String {
        return when (currentMode) {
            Mode.NORMAL -> {
                "  ${TerminalColors.emphasis("-- NORMAL --")} Single-key commands active (i=insert, ?=help)"
            }
            Mode.INSERT -> {
                "  ${TerminalColors.dim("-- INSERT --")} Type commands (Esc=normal mode)"
            }
            Mode.OBSERVING -> {
                val filterInfo = if (currentFilter != null) {
                    " | Filter: ${TerminalColors.emphasis(currentFilter!!)}"
                } else {
                    ""
                }
                "  ${TerminalColors.emphasis("-- OBSERVING --")} Press Enter to stop | Ctrl+E to cycle filter$filterInfo"
            }
        }
    }
}
