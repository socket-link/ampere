package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Interface for renderers that can work within constrained dimensions.
 *
 * Pane renderers are designed to be composable within layout managers
 * like SplitPaneLayout. Unlike full-screen renderers that clear and
 * redraw the entire terminal, pane renderers produce content sized
 * to fit within allocated space.
 */
interface PaneRenderer {
    /**
     * Render content to fit within the specified dimensions.
     *
     * @param width Available width in characters
     * @param height Available height in lines
     * @return List of lines, each padded/truncated to exactly [width] characters.
     *         The list should have exactly [height] lines.
     */
    fun render(width: Int, height: Int): List<String>
}

/**
 * Extension function to pad or truncate a line to exact width.
 * Handles ANSI escape sequences by only counting visible characters.
 */
fun String.fitToWidth(width: Int): String {
    val visibleLength = this.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "").length
    return when {
        visibleLength < width -> this + " ".repeat(width - visibleLength)
        visibleLength > width -> truncateToVisibleWidth(width)
        else -> this
    }
}

/**
 * Truncate a string to a visible width, preserving ANSI codes.
 */
private fun String.truncateToVisibleWidth(maxWidth: Int): String {
    val result = StringBuilder()
    var visibleCount = 0
    var i = 0

    while (i < this.length && visibleCount < maxWidth) {
        if (this[i] == '\u001B' && i + 1 < this.length && this[i + 1] == '[') {
            // Copy entire ANSI escape sequence
            result.append(this[i])
            i++
            while (i < this.length && !this[i].isLetter()) {
                result.append(this[i])
                i++
            }
            if (i < this.length) {
                result.append(this[i])
                i++
            }
        } else {
            result.append(this[i])
            visibleCount++
            i++
        }
    }

    // Reset styling at the end if we truncated mid-style
    result.append("\u001B[0m")
    return result.toString()
}

/**
 * Pad a list of lines to exactly the specified height.
 */
fun List<String>.fitToHeight(height: Int, width: Int): List<String> {
    val emptyLine = " ".repeat(width)
    return when {
        this.size < height -> this + List(height - this.size) { emptyLine }
        this.size > height -> this.take(height)
        else -> this
    }
}

/**
 * Renders a section header with separator and spacing.
 *
 * Format:
 * ```
 * SECTION TITLE
 * ────────────────────────
 *
 * ```
 *
 * @param title The section title (rendered in uppercase with cyan color)
 * @param width Available width in characters
 * @param terminal Terminal instance for rendering styled text
 * @return List of 3 lines: title, separator, blank line
 */
fun renderSectionHeader(title: String, width: Int, terminal: Terminal): List<String> {
    val header = terminal.render(bold(TextColors.cyan(title.uppercase())))
    val separator = terminal.render(dim("─".repeat(width)))
    return listOf(header, separator, "")
}

/**
 * Renders a sub-section header with lighter styling.
 *
 * Format:
 * ```
 * ─── Sub Title ───────
 *
 * ```
 *
 * @param title The sub-section title
 * @param width Available width in characters
 * @param terminal Terminal instance for rendering styled text
 * @return List of 2 lines: styled title, blank line
 */
fun renderSubHeader(title: String, width: Int, terminal: Terminal): List<String> {
    val prefix = "─── "
    val suffix = " "
    val remainingDashes = (width - prefix.length - suffix.length - title.length).coerceAtLeast(3)
    val line = terminal.render(dim("$prefix$title$suffix${"─".repeat(remainingDashes)}"))
    return listOf(line, "")
}
