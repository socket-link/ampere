package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal

/**
 * A layout manager that arranges two panes side-by-side or stacked.
 *
 * SplitPaneLayout handles:
 * - Calculating pane dimensions from terminal size
 * - Rendering both panes with proper sizing
 * - Drawing a divider between panes
 * - Composing the final screen buffer
 */
class SplitPaneLayout(
    private val terminal: Terminal,
    private val orientation: Orientation = Orientation.VERTICAL,
    private val ratio: Float = 0.5f,
    private val dividerStyle: DividerStyle = DividerStyle.SINGLE
) {
    /**
     * Layout orientation.
     * VERTICAL = panes side-by-side (left | right)
     * HORIZONTAL = panes stacked (top / bottom)
     */
    enum class Orientation { VERTICAL, HORIZONTAL }

    /**
     * Style for the divider between panes.
     */
    enum class DividerStyle {
        SINGLE,  // │ or ─
        DOUBLE,  // ║ or ═
        NONE     // No divider (panes touch)
    }

    private val dividerWidth: Int = when (dividerStyle) {
        DividerStyle.NONE -> 0
        else -> 1
    }

    /**
     * Render both panes into a combined screen buffer.
     *
     * @param leftPane The left (or top) pane renderer
     * @param rightPane The right (or bottom) pane renderer
     * @return Complete screen buffer ready for display
     */
    fun render(leftPane: PaneRenderer, rightPane: PaneRenderer): String {
        val totalWidth = terminal.info.width
        val totalHeight = terminal.info.height - 1  // Leave room for status line

        return when (orientation) {
            Orientation.VERTICAL -> renderVertical(leftPane, rightPane, totalWidth, totalHeight)
            Orientation.HORIZONTAL -> renderHorizontal(leftPane, rightPane, totalWidth, totalHeight)
        }
    }

    private fun renderVertical(
        leftPane: PaneRenderer,
        rightPane: PaneRenderer,
        totalWidth: Int,
        totalHeight: Int
    ): String {
        val leftWidth = ((totalWidth - dividerWidth) * ratio).toInt()
        val rightWidth = totalWidth - leftWidth - dividerWidth

        val leftLines = leftPane.render(leftWidth, totalHeight)
            .fitToHeight(totalHeight, leftWidth)
            .map { it.fitToWidth(leftWidth) }

        val rightLines = rightPane.render(rightWidth, totalHeight)
            .fitToHeight(totalHeight, rightWidth)
            .map { it.fitToWidth(rightWidth) }

        val dividerChar = when (dividerStyle) {
            DividerStyle.SINGLE -> "│"
            DividerStyle.DOUBLE -> "║"
            DividerStyle.NONE -> ""
        }

        return buildString {
            // Clear screen and move cursor home
            append("\u001B[2J")
            append("\u001B[H")

            for (i in 0 until totalHeight) {
                val leftLine = leftLines.getOrElse(i) { " ".repeat(leftWidth) }
                val rightLine = rightLines.getOrElse(i) { " ".repeat(rightWidth) }

                append(leftLine)
                append(dividerChar)
                append(rightLine)
                if (i < totalHeight - 1) append("\n")
            }
        }
    }

    private fun renderHorizontal(
        topPane: PaneRenderer,
        bottomPane: PaneRenderer,
        totalWidth: Int,
        totalHeight: Int
    ): String {
        val topHeight = ((totalHeight - dividerWidth) * ratio).toInt()
        val bottomHeight = totalHeight - topHeight - dividerWidth

        val topLines = topPane.render(totalWidth, topHeight)
            .fitToHeight(topHeight, totalWidth)
            .map { it.fitToWidth(totalWidth) }

        val bottomLines = bottomPane.render(totalWidth, bottomHeight)
            .fitToHeight(bottomHeight, totalWidth)
            .map { it.fitToWidth(totalWidth) }

        val dividerLine = when (dividerStyle) {
            DividerStyle.SINGLE -> "─".repeat(totalWidth)
            DividerStyle.DOUBLE -> "═".repeat(totalWidth)
            DividerStyle.NONE -> null
        }

        return buildString {
            // Clear screen and move cursor home
            append("\u001B[2J")
            append("\u001B[H")

            topLines.forEach { line ->
                append(line)
                append("\n")
            }

            if (dividerLine != null) {
                append(dividerLine)
                append("\n")
            }

            bottomLines.forEachIndexed { index, line ->
                append(line)
                if (index < bottomLines.size - 1) append("\n")
            }
        }
    }

    /**
     * Get the dimensions for the left/top pane.
     */
    fun getFirstPaneDimensions(): Pair<Int, Int> {
        val totalWidth = terminal.info.width
        val totalHeight = terminal.info.height - 1

        return when (orientation) {
            Orientation.VERTICAL -> {
                val width = ((totalWidth - dividerWidth) * ratio).toInt()
                Pair(width, totalHeight)
            }
            Orientation.HORIZONTAL -> {
                val height = ((totalHeight - dividerWidth) * ratio).toInt()
                Pair(totalWidth, height)
            }
        }
    }

    /**
     * Get the dimensions for the right/bottom pane.
     */
    fun getSecondPaneDimensions(): Pair<Int, Int> {
        val totalWidth = terminal.info.width
        val totalHeight = terminal.info.height - 1

        return when (orientation) {
            Orientation.VERTICAL -> {
                val firstWidth = ((totalWidth - dividerWidth) * ratio).toInt()
                val width = totalWidth - firstWidth - dividerWidth
                Pair(width, totalHeight)
            }
            Orientation.HORIZONTAL -> {
                val firstHeight = ((totalHeight - dividerWidth) * ratio).toInt()
                val height = totalHeight - firstHeight - dividerWidth
                Pair(totalWidth, height)
            }
        }
    }
}
