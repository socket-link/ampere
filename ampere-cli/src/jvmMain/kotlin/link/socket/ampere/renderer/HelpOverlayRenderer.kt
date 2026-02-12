package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import link.socket.ampere.cli.help.CommandRegistry

/**
 * Renders help overlay showing keyboard shortcuts and available commands.
 */
class HelpOverlayRenderer(
    private val terminal: Terminal
) {
    fun render(): String {
        return buildString {
            // Box drawing characters for a nice border
            val horizontal = "─"
            val vertical = "│"
            val topLeft = "┌"
            val topRight = "┐"
            val bottomLeft = "└"
            val bottomRight = "┘"

            val width = 70
            val title = " AMPERE Dashboard Help "

            // Top border with title
            append("\u001B[2J\u001B[H")  // Clear and home
            append("\n\n\n")  // Some top padding

            append("  ")
            append(terminal.render(TextColors.cyan(topLeft)))
            append(terminal.render(TextColors.cyan(horizontal.repeat(width - 2))))
            append(terminal.render(TextColors.cyan(topRight)))
            append("\n")

            // Title
            append("  ")
            append(terminal.render(TextColors.cyan(vertical)))
            val titlePadding = (width - title.length - 2) / 2
            append(" ".repeat(titlePadding))
            append(terminal.render(bold(TextColors.white(title))))
            append(" ".repeat(width - title.length - titlePadding - 2))
            append(terminal.render(TextColors.cyan(vertical)))
            append("\n")

            // Separator
            append("  ")
            append(terminal.render(TextColors.cyan(vertical)))
            append(horizontal.repeat(width - 2))
            append(terminal.render(TextColors.cyan(vertical)))
            append("\n")

            // Content sections
            fun addSection(title: String, items: List<Pair<String, String>>) {
                append("  ")
                append(terminal.render(TextColors.cyan(vertical)))
                append(" ")
                append(terminal.render(bold(TextColors.yellow(title))))
                append(" ".repeat(width - title.length - 3))
                append(terminal.render(TextColors.cyan(vertical)))
                append("\n")

                items.forEach { (key, description) ->
                    append("  ")
                    append(terminal.render(TextColors.cyan(vertical)))
                    append("   ")
                    append(terminal.render(TextColors.green(key.padEnd(12))))

                    // Truncate description if too long for terminal width
                    val maxDescLength = width - 3 - 12 - 2 - 4 // margins and borders
                    val truncatedDesc = if (description.length > maxDescLength) {
                        description.take(maxDescLength - 3) + "..."
                    } else {
                        description
                    }
                    append(terminal.render(dim(truncatedDesc)))

                    val lineLength = 3 + 12 + truncatedDesc.length
                    val padding = (width - lineLength - 2).coerceAtLeast(0)
                    append(" ".repeat(padding))
                    append(terminal.render(TextColors.cyan(vertical)))
                    append("\n")
                }

                append("  ")
                append(terminal.render(TextColors.cyan(vertical)))
                append(" ".repeat(width - 2))
                append(terminal.render(TextColors.cyan(vertical)))
                append("\n")
            }

            // Viewing Modes section - from registry
            val viewingModes = CommandRegistry.shortcutsByCategory(CommandRegistry.ShortcutCategory.VIEWING_MODES)
                .map { it.formatKeyForHelp() to it.description }
            addSection("Viewing Modes", viewingModes)

            // Options & Commands section - from registry + ESC hint
            val options = CommandRegistry.shortcutsByCategory(CommandRegistry.ShortcutCategory.OPTIONS)
                .filter { it.key != '1' } // Already in viewing modes
                .map { it.formatKeyForHelp() to it.description }
                .toMutableList()
            options.add("ESC" to "Close help / Cancel command mode")
            addSection("Options & Commands", options)

            // Command Mode section - from registry
            val commands = CommandRegistry.commands.map { cmd ->
                val keyPart = if (cmd.usage != null) {
                    ":${cmd.name} ${cmd.usage}"
                } else {
                    ":${cmd.name}"
                }
                keyPart to cmd.description
            }
            addSection("Command Mode", commands)

            // Bottom border
            append("  ")
            append(terminal.render(TextColors.cyan(bottomLeft)))
            append(terminal.render(TextColors.cyan(horizontal.repeat(width - 2))))
            append(terminal.render(TextColors.cyan(bottomRight)))
            append("\n\n")

            // Footer
            append("  ")
            append(terminal.render(dim("Press ESC or 'h' to close help")))
            append("\n")
        }
    }
}
