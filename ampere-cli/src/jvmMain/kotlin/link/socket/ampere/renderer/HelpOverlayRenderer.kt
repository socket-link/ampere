package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal

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

            addSection("Viewing Modes", listOf(
                "d" to "Dashboard - System vitals, agent status, recent events",
                "e" to "Event Stream - Filtered stream of significant events",
                "m" to "Memory Ops - Knowledge recall/storage patterns",
                "1-9" to "Agent Focus - Detailed view of specific agent"
            ))

            addSection("Options & Commands", listOf(
                "v" to "Toggle verbose mode (show/hide routine events)",
                ":" to "Command mode - Issue commands to the system",
                "h  or  ?" to "Toggle this help screen",
                "ESC" to "Close help / Cancel command mode",
                "q  or  Ctrl+C" to "Exit AMPERE dashboard"
            ))

            addSection("Command Mode", listOf(
                ":help" to "Show available commands",
                ":agents" to "List all active agents",
                ":ticket <id>" to "Show ticket details",
                ":thread <id>" to "Show conversation thread",
                ":quit" to "Exit dashboard"
            ))

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
