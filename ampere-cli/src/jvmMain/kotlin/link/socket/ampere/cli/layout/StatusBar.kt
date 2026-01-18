package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import link.socket.ampere.cli.help.CommandRegistry
import link.socket.ampere.repl.TerminalSymbols

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
     *
     * Status lifecycle:
     * - IDLE: No active tasks, system ready
     * - THINKING: Agent in planning/perceive phase
     * - WORKING: Agent actively executing
     * - WAITING: Blocked on human input
     * - COMPLETED: Task just finished successfully
     * - ATTENTION_NEEDED: Error or exception occurred
     */
    enum class SystemStatus {
        IDLE,
        THINKING,
        WORKING,
        WAITING,
        COMPLETED,
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
     * @param focusedAgent Currently focused agent index (null if none)
     * @param inputHint Pending input hint (e.g., "agent [1-9]")
     * @return Formatted status bar string
     */
    fun render(
        width: Int,
        shortcuts: List<Shortcut>,
        status: SystemStatus,
        focusedAgent: Int? = null,
        inputHint: String? = null
    ): String {
        // If there's a pending input hint, show it prominently
        if (inputHint != null) {
            val hintStr = terminal.render(bold(TextColors.yellow(": $inputHint")))
            val escHint = terminal.render(dim(" [ESC cancel]"))
            val statusStr = renderStatus(status)

            val hintVisible = hintStr.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length
            val escVisible = escHint.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length
            val statusVisible = statusStr.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length

            val padding = (width - hintVisible - escVisible - statusVisible).coerceAtLeast(1)
            return "$hintStr$escHint${" ".repeat(padding)}$statusStr"
        }

        val shortcutsStr = renderShortcuts(shortcuts)
        val statusStr = renderStatus(status)

        // If an agent is focused, show hint to return
        val focusHint = if (focusedAgent != null) {
            terminal.render(dim(" [ESC] return"))
        } else {
            ""
        }

        // Calculate padding between shortcuts and status
        val shortcutsVisible = shortcutsStr.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length
        val statusVisible = statusStr.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length
        val hintVisible = focusHint.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length

        val padding = (width - shortcutsVisible - statusVisible - hintVisible).coerceAtLeast(1)

        return "$shortcutsStr$focusHint${" ".repeat(padding)}$statusStr"
    }

    private fun renderShortcuts(shortcuts: List<Shortcut>): String {
        // Group shortcuts by category:
        // - View modes: d, e, m (dashboard, events, memory)
        // - Toggles: v (verbose)
        // - Help: h (help)
        // - Exit: q (quit)
        val viewModes = shortcuts.filter { it.key in listOf('d', 'e', 'm') }
        val toggles = shortcuts.filter { it.key == 'v' }
        val help = shortcuts.filter { it.key == 'h' }
        val exit = shortcuts.filter { it.key == 'q' }

        val parts = mutableListOf<String>()

        // View modes group
        if (viewModes.isNotEmpty()) {
            parts.add(renderShortcutGroup(viewModes))
        }

        // Toggles group
        if (toggles.isNotEmpty()) {
            parts.add(renderShortcutGroup(toggles))
        }

        // Help group
        if (help.isNotEmpty()) {
            parts.add(renderShortcutGroup(help))
        }

        // Exit group
        if (exit.isNotEmpty()) {
            parts.add(renderShortcutGroup(exit))
        }

        // Join groups with separator (respects Unicode capabilities)
        val separator = terminal.render(dim(" ${TerminalSymbols.Separator.vertical} "))
        return parts.joinToString(separator)
    }

    private fun renderShortcutGroup(shortcuts: List<Shortcut>): String {
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
            SystemStatus.IDLE -> TextColors.gray
            SystemStatus.THINKING -> TextColors.yellow
            SystemStatus.WORKING -> TextColors.blue
            SystemStatus.WAITING -> TextColors.magenta
            SystemStatus.COMPLETED -> TextColors.green
            SystemStatus.ATTENTION_NEEDED -> TextColors.red
        }

        // Status indicator (pulsing for active states, respects Unicode capabilities)
        val indicator = when (status) {
            SystemStatus.WORKING -> terminal.render(TextColors.blue(TerminalSymbols.Status.filledCircle))
            SystemStatus.THINKING -> terminal.render(TextColors.yellow(TerminalSymbols.Status.halfCircle))
            SystemStatus.WAITING -> terminal.render(TextColors.magenta(TerminalSymbols.Status.dotCircle))
            SystemStatus.COMPLETED -> terminal.render(TextColors.green(TerminalSymbols.Status.check))
            SystemStatus.ATTENTION_NEEDED -> terminal.render(TextColors.red("!"))
            SystemStatus.IDLE -> terminal.render(dim(TerminalSymbols.Status.emptyCircle))
        }

        val statusText = status.name.replace("_", " ")
        return "$indicator ${terminal.render(statusColor(statusText))}"
    }

    companion object {
        /**
         * Default shortcuts for the Jazz demo.
         * Pulls from CommandRegistry for consistency.
         */
        fun defaultShortcuts(activeMode: String? = null): List<Shortcut> {
            return CommandRegistry.statusBarShortcuts().map { def ->
                val isActive = when (def.key) {
                    'a' -> activeMode == "agent_focus"
                    'd' -> activeMode == "dashboard"
                    'e' -> activeMode == "events"
                    'm' -> activeMode == "memory"
                    else -> false
                }
                Shortcut(def.key, def.label, isActive)
            }
        }
    }
}
