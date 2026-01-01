package link.socket.ampere.cli.help

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Unified help renderer that formats help from CommandRegistry.
 *
 * Provides two output formats:
 * - Overlay format: Bordered box for 'h' key help display
 * - Text format: Plain text for :help command output
 */
class HelpRenderer(private val terminal: Terminal) {

    /**
     * Render help as a bordered overlay (for 'h' key).
     */
    fun renderOverlay(): String {
        return buildString {
            val horizontal = "\u2500"
            val vertical = "\u2502"
            val topLeft = "\u250C"
            val topRight = "\u2510"
            val bottomLeft = "\u2514"
            val bottomRight = "\u2518"

            val width = 70
            val title = " AMPERE Dashboard Help "

            // Clear and position
            append("\u001B[2J\u001B[H")
            append("\n\n\n")

            // Top border with title
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

            // Viewing Modes section
            appendSection(
                title = "Viewing Modes",
                items = CommandRegistry.shortcutsByCategory(CommandRegistry.ShortcutCategory.VIEWING_MODES)
                    .map { it.formatKeyForHelp() to it.description },
                width = width,
                vertical = vertical,
            )

            // Options & Commands section
            appendSection(
                title = "Options & Commands",
                items = CommandRegistry.shortcutsByCategory(CommandRegistry.ShortcutCategory.OPTIONS)
                    .filter { it.key != '1' } // Skip the 1-9 as it's in viewing modes
                    .map { it.formatKeyForHelp() to it.description },
                width = width,
                vertical = vertical,
            )

            // Add ESC hint
            append("  ")
            append(terminal.render(TextColors.cyan(vertical)))
            append("   ")
            append(terminal.render(TextColors.green("ESC".padEnd(12))))
            append(terminal.render(dim("Close help / Cancel command mode")))
            val escLineLen = 3 + 12 + "Close help / Cancel command mode".length
            append(" ".repeat((width - escLineLen - 2).coerceAtLeast(0)))
            append(terminal.render(TextColors.cyan(vertical)))
            append("\n")

            // Blank line
            append("  ")
            append(terminal.render(TextColors.cyan(vertical)))
            append(" ".repeat(width - 2))
            append(terminal.render(TextColors.cyan(vertical)))
            append("\n")

            // Command Mode section
            appendSection(
                title = "Command Mode",
                items = CommandRegistry.commands.map { cmd ->
                    val keyPart = if (cmd.usage != null) {
                        ":${cmd.name} ${cmd.usage}"
                    } else {
                        ":${cmd.name}"
                    }
                    keyPart to cmd.description
                },
                width = width,
                vertical = vertical,
            )

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

    private fun StringBuilder.appendSection(
        title: String,
        items: List<Pair<String, String>>,
        width: Int,
        vertical: String,
    ) {
        // Section header
        append("  ")
        append(terminal.render(TextColors.cyan(vertical)))
        append(" ")
        append(terminal.render(bold(TextColors.yellow(title))))
        append(" ".repeat(width - title.length - 3))
        append(terminal.render(TextColors.cyan(vertical)))
        append("\n")

        // Items
        items.forEach { (key, description) ->
            append("  ")
            append(terminal.render(TextColors.cyan(vertical)))
            append("   ")
            append(terminal.render(TextColors.green(key.padEnd(12))))

            val maxDescLength = width - 3 - 12 - 2 - 4
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

        // Blank line after section
        append("  ")
        append(terminal.render(TextColors.cyan(vertical)))
        append(" ".repeat(width - 2))
        append(terminal.render(TextColors.cyan(vertical)))
        append("\n")
    }

    /**
     * Render help as plain text (for :help command).
     */
    fun renderText(): String {
        return buildString {
            appendLine("Available commands:")
            appendLine()

            CommandRegistry.commands.forEach { cmd ->
                val formatted = cmd.formatForHelp().padEnd(24)
                appendLine("  $formatted ${cmd.description}")
            }

            appendLine()
            appendLine("Press ESC to cancel command mode")
        }
    }

    /**
     * Render a compact shortcuts line for status bar context.
     */
    fun renderCompactShortcuts(): String {
        return CommandRegistry.statusBarShortcuts()
            .joinToString(" ") { "[${it.key}]${it.label}" }
    }
}
