package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Status bar component for the bottom of the demo layout.
 *
 * Shows keyboard shortcuts on the left and system status on the right.
 *
 * Example:
 * [d]ashboard [e]vents [m]emory [v]erbose [h]elp [q]uit    Status: WORKING
 */
class StatusBar(private val terminal: Terminal) {

    /**
     * System status indicator.
     */
    enum class SystemStatus {
        IDLE,
        WORKING,
        ATTENTION_NEEDED
    }

    /**
     * A keyboard shortcut to display.
     */
    data class Shortcut(
        val key: Char,
        val label: String,
        val isActive: Boolean = false
    )

    /**
     * Render the status bar.
     *
     * @param width Total width available
     * @param shortcuts List of keyboard shortcuts to display
     * @param status Current system status
     * @param expandedEvent Currently expanded event index (null if none)
     * @return Formatted status bar string
     */
    fun render(
        width: Int,
        shortcuts: List<Shortcut>,
        status: SystemStatus,
        expandedEvent: Int? = null
    ): String {
        val shortcutsStr = renderShortcuts(shortcuts)
        val statusStr = renderStatus(status)

        // If an event is expanded, show hint
        val expandHint = if (expandedEvent != null) {
            terminal.render(dim(" [ESC] collapse"))
        } else {
            ""
        }

        // Calculate padding between shortcuts and status
        val shortcutsVisible = shortcutsStr.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length
        val statusVisible = statusStr.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length
        val hintVisible = expandHint.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length

        val padding = (width - shortcutsVisible - statusVisible - hintVisible).coerceAtLeast(1)

        return "$shortcutsStr$expandHint${" ".repeat(padding)}$statusStr"
    }

    private fun renderShortcuts(shortcuts: List<Shortcut>): String {
        return shortcuts.joinToString(" ") { shortcut ->
            val keyPart = terminal.render(
                if (shortcut.isActive) {
                    bold(TextColors.cyan("[${shortcut.key}]"))
                } else {
                    TextColors.white("[${shortcut.key}]")
                }
            )
            val labelPart = terminal.render(
                if (shortcut.isActive) {
                    TextColors.cyan(shortcut.label)
                } else {
                    dim(shortcut.label)
                }
            )
            "$keyPart$labelPart"
        }
    }

    private fun renderStatus(status: SystemStatus): String {
        val statusColor = when (status) {
            SystemStatus.IDLE -> TextColors.green
            SystemStatus.WORKING -> TextColors.blue
            SystemStatus.ATTENTION_NEEDED -> TextColors.red
        }

        val statusText = status.name.replace("_", " ")
        return terminal.render(dim("Status: ")) + terminal.render(statusColor(statusText))
    }

    companion object {
        /**
         * Default shortcuts for the Jazz demo.
         */
        fun defaultShortcuts(activeMode: String? = null): List<Shortcut> = listOf(
            Shortcut('d', "dashboard", activeMode == "dashboard"),
            Shortcut('e', "events", activeMode == "events"),
            Shortcut('m', "memory", activeMode == "memory"),
            Shortcut('v', "verbose"),
            Shortcut('h', "help"),
            Shortcut('q', "quit")
        )
    }
}
