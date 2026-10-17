package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import link.socket.ampere.cli.help.CommandRegistry
import link.socket.ampere.terminal.TerminalSymbols
import link.socket.ampere.ui.inference.OnDeviceIndicatorModel
import link.socket.ampere.ui.inference.OnDeviceIndicatorTone

/**
 * Status bar component for the bottom of the demo layout.
 *
 * Shows keyboard shortcuts on the left and system status on the right. With an on-device engine
 * bound, the on-device indicator sits just before the status (AMPR-375).
 *
 * Example:
 * [d]ashboard [e]vents [m]emory [v]erbose [h]elp [q]uit    ◇ On-device ready │ ● WORKING
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
     * An escalation-specific shortcut displayed during AWAITING_HUMAN state.
     * These replace normal shortcuts when escalation is active.
     */
    data class EscalationShortcut(
        val key: String,
        val label: String
    )

    /**
     * Render the status bar.
     *
     * @param width Total width available
     * @param shortcuts List of keyboard shortcuts to display
     * @param status Current system status
     * @param focusedAgent Currently focused agent index (null if none)
     * @param inputHint Pending input hint (e.g., "agent [1-9]") - deprecated, use escalationShortcuts instead
     * @param escalationShortcuts Shortcuts to display during escalation (replaces normal shortcuts)
     * @param onDevice The on-device indicator (AMPR-375), shown beside the status when there is room.
     *   Null, when no on-device engine is bound, renders the bar exactly as before.
     * @return Formatted status bar string
     */
    fun render(
        width: Int,
        shortcuts: List<Shortcut>,
        status: SystemStatus,
        focusedAgent: Int? = null,
        inputHint: String? = null,
        escalationShortcuts: List<EscalationShortcut>? = null,
        onDevice: OnDeviceIndicatorModel? = null,
    ): String {
        val left = when {
            // If escalation shortcuts are provided, render them instead of normal shortcuts
            escalationShortcuts != null && escalationShortcuts.isNotEmpty() ->
                renderEscalationShortcuts(escalationShortcuts)

            // Legacy input hint handling (for non-escalation input modes like AWAITING_AGENT)
            inputHint != null ->
                terminal.render(bold(TextColors.yellow(": $inputHint"))) + terminal.render(dim(" [ESC cancel]"))

            // If an agent is focused, show hint to return
            else -> renderShortcuts(shortcuts) + if (focusedAgent != null) terminal.render(dim(" [ESC] return")) else ""
        }
        val right = renderRight(status, onDevice, room = width - visibleLength(left) - 1)

        // Pad between the shortcuts and the status
        val padding = (width - visibleLength(left) - visibleLength(right)).coerceAtLeast(1)
        return "$left${" ".repeat(padding)}$right"
    }

    /**
     * The status, preceded by the on-device segment when [room] allows: with its detail if that
     * fits, by its title alone if only that does, and not at all otherwise. The status always shows.
     */
    private fun renderRight(status: SystemStatus, onDevice: OnDeviceIndicatorModel?, room: Int): String {
        val statusStr = renderStatus(status)
        if (onDevice == null) return statusStr

        val separator = terminal.render(dim(" ${TerminalSymbols.Separator.vertical} "))
        return listOf(true, false)
            .map { withDetail -> renderOnDevice(onDevice, withDetail) + separator + statusStr }
            .firstOrNull { visibleLength(it) <= room }
            ?: statusStr
    }

    /**
     * The on-device segment, in the indicator's own words (`ui/inference/OnDeviceIndicatorModel.kt`):
     * a filled marker while the model is generating, an empty one at rest.
     */
    private fun renderOnDevice(model: OnDeviceIndicatorModel, withDetail: Boolean): String {
        val color = when (model.tone) {
            OnDeviceIndicatorTone.IN_USE -> TextColors.cyan
            OnDeviceIndicatorTone.READY -> TextColors.green
            OnDeviceIndicatorTone.UNAVAILABLE -> TextColors.yellow
            OnDeviceIndicatorTone.UNKNOWN -> TextColors.gray
        }
        val marker = if (model.tone == OnDeviceIndicatorTone.IN_USE) {
            TerminalSymbols.Status.onDevice
        } else {
            TerminalSymbols.Status.onDeviceIdle
        }
        val title = if (model.tone == OnDeviceIndicatorTone.IN_USE) bold(color(model.title)) else color(model.title)
        val detail = model.detail?.takeIf { withDetail && it.isNotBlank() }
            ?.let { terminal.render(dim(" · $it")) }
            .orEmpty()
        return terminal.render(color(marker)) + " " + terminal.render(title) + detail
    }

    private fun visibleLength(text: String): Int = text.replace(ANSI_ESCAPE, "").length

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

    /**
     * Render escalation-specific shortcuts.
     * Uses magenta color scheme to match WAITING status.
     */
    private fun renderEscalationShortcuts(shortcuts: List<EscalationShortcut>): String {
        return shortcuts.joinToString("  ") { shortcut ->
            val keyPart = terminal.render(bold(TextColors.magenta("[${shortcut.key}]")))
            val labelPart = terminal.render(TextColors.white(shortcut.label))
            "$keyPart $labelPart"
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
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*[a-zA-Z]")

        /**
         * Default shortcuts for the interactive TUI.
         * Pulls from CommandRegistry for consistency.
         */
        fun defaultShortcuts(activeMode: String? = null): List<Shortcut> {
            return CommandRegistry.statusBarShortcuts().map { def ->
                val isActive = when (def.key) {
                    'a' -> activeMode == "agent_focus"
                    'd' -> activeMode == "dashboard"
                    'e' -> activeMode == "events"
                    'm' -> activeMode == "memory"
                    't' -> activeMode == "tasks"
                    else -> false
                }
                Shortcut(def.key, def.label, isActive)
            }
        }

        /**
         * Create escalation shortcuts from options.
         * Adds ESC/skip as the last option.
         *
         * @param options List of key-label pairs (e.g., "A" to "Option A")
         * @return List of EscalationShortcut including ESC/skip
         */
        fun escalationShortcuts(options: List<Pair<String, String>>): List<EscalationShortcut> {
            val shortcuts = options.map { (key, label) ->
                EscalationShortcut(key, label)
            }.toMutableList()
            // Always add ESC/skip at the end
            shortcuts.add(EscalationShortcut("ESC", "skip"))
            return shortcuts
        }
    }
}
