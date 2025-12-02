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
    private var eventCount: Int = 0

    fun render(mode: Mode, filter: String? = null, message: String? = null, eventCount: Int = 0) {
        currentMode = mode
        currentFilter = filter
        customMessage = message
        this.eventCount = eventCount

        val width = terminal.width
        val separator = buildSeparator(width)

        // Top separator
        terminal.writer().println(separator)

        // Status line with mode and context
        val statusLine = buildStatusLine()
        terminal.writer().println(statusLine)

        // Render prompt based on mode
        renderPrompt(mode)
    }

    private fun buildSeparator(width: Int): String {
        // Thin line separator
        return TerminalColors.dim("─".repeat(width.coerceAtLeast(1)))
    }

    private fun renderPrompt(mode: Mode) {
        val prompt = when (mode) {
            Mode.NORMAL -> TerminalColors.highlight("⚡") + " "
            Mode.INSERT -> TerminalColors.highlight("⚡") + " "
            Mode.OBSERVING -> ""  // No prompt during observation
        }

        terminal.writer().print(prompt)
        terminal.writer().flush()
    }

    private fun buildStatusLine(): String {
        val modeIndicator = buildModeIndicator()
        val contextInfo = buildContextInfo()
        val hints = buildHints()

        val fullLine = "  $modeIndicator$contextInfo$hints"

        // Truncate if too wide for terminal
        val maxWidth = terminal.width - 2
        return if (fullLine.length > maxWidth) {
            fullLine.substring(0, maxWidth) + "…"
        } else {
            fullLine
        }
    }

    private fun buildModeIndicator(): String {
        return when (currentMode) {
            Mode.NORMAL -> TerminalColors.emphasis("-- NORMAL --")
            Mode.INSERT -> TerminalColors.dim("-- INSERT --")
            Mode.OBSERVING -> TerminalColors.emphasis("-- OBSERVING --")
        }
    }

    private fun buildContextInfo(): String {
        return when (currentMode) {
            Mode.OBSERVING -> {
                val filterDisplay = if (currentFilter != null) {
                    " | Filter: ${TerminalColors.emphasis(currentFilter!!)}"
                } else {
                    ""
                }
                val countDisplay = if (eventCount > 0) {
                    " | Events: ${TerminalColors.dim(eventCount.toString())}"
                } else {
                    ""
                }
                "$filterDisplay$countDisplay"
            }
            else -> ""
        }
    }

    private fun buildHints(): String {
        return when (currentMode) {
            Mode.NORMAL -> {
                " ${TerminalColors.dim("(w/s/t/o=commands, i=insert, ?=help)")}"
            }
            Mode.INSERT -> {
                " ${TerminalColors.dim("(Esc=normal, Tab=complete)")}"
            }
            Mode.OBSERVING -> {
                " ${TerminalColors.dim("(Enter=stop, Ctrl+E=filter, Ctrl+D=disconnect)")}"
            }
        }
    }

    /**
     * Update event count during observation.
     */
    fun updateEventCount(count: Int) {
        eventCount = count
        // Optionally re-render just the count part
    }
}
