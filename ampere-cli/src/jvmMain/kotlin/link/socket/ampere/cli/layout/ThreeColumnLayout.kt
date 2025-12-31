package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal

/**
 * A layout manager that arranges three panes in columns with a status bar.
 *
 * Layout structure:
 * ┌──────────────────────┬────────────────────────────────┬─────────────────┐
 * │   LEFT PANE (30%)    │     MIDDLE PANE (50%)          │ RIGHT PANE (20%)│
 * │   Event stream       │     Main interaction           │ Agent/Memory    │
 * ├──────────────────────┴────────────────────────────────┴─────────────────┤
 * │ Status bar with shortcuts and system status                             │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
class ThreeColumnLayout(
    private val terminal: Terminal,
    private val leftRatio: Float = 0.30f,
    private val middleRatio: Float = 0.50f,
    private val rightRatio: Float = 0.20f
) {
    private val dividerChar = "│"
    private val horizontalDivider = "─"

    /**
     * Render all three panes with a status bar at the bottom.
     */
    fun render(
        leftPane: PaneRenderer,
        middlePane: PaneRenderer,
        rightPane: PaneRenderer,
        statusBar: String
    ): String {
        val totalWidth = terminal.info.width
        val totalHeight = terminal.info.height

        // Reserve 2 lines for status bar (divider + content)
        val contentHeight = totalHeight - 2

        // Calculate column widths (account for 2 dividers)
        val availableWidth = totalWidth - 2
        val leftWidth = (availableWidth * leftRatio).toInt()
        val rightWidth = (availableWidth * rightRatio).toInt()
        val middleWidth = availableWidth - leftWidth - rightWidth

        // Render each pane
        val leftLines = leftPane.render(leftWidth, contentHeight)
            .fitToHeight(contentHeight, leftWidth)
            .map { it.fitToWidth(leftWidth) }

        val middleLines = middlePane.render(middleWidth, contentHeight)
            .fitToHeight(contentHeight, middleWidth)
            .map { it.fitToWidth(middleWidth) }

        val rightLines = rightPane.render(rightWidth, contentHeight)
            .fitToHeight(contentHeight, rightWidth)
            .map { it.fitToWidth(rightWidth) }

        return buildString {
            // Clear screen and home cursor
            append("\u001B[2J")
            append("\u001B[H")

            // Render content rows
            for (i in 0 until contentHeight) {
                val left = leftLines.getOrElse(i) { " ".repeat(leftWidth) }
                val middle = middleLines.getOrElse(i) { " ".repeat(middleWidth) }
                val right = rightLines.getOrElse(i) { " ".repeat(rightWidth) }

                append(left)
                append(dividerChar)
                append(middle)
                append(dividerChar)
                append(right)
                append("\n")
            }

            // Horizontal divider above status bar
            append(horizontalDivider.repeat(totalWidth))
            append("\n")

            // Status bar (fit to width)
            append(statusBar.fitToWidth(totalWidth))
        }
    }

    /**
     * Get dimensions for each pane.
     */
    fun getPaneDimensions(): Triple<Pair<Int, Int>, Pair<Int, Int>, Pair<Int, Int>> {
        val totalWidth = terminal.info.width
        val totalHeight = terminal.info.height
        val contentHeight = totalHeight - 2

        val availableWidth = totalWidth - 2
        val leftWidth = (availableWidth * leftRatio).toInt()
        val rightWidth = (availableWidth * rightRatio).toInt()
        val middleWidth = availableWidth - leftWidth - rightWidth

        return Triple(
            Pair(leftWidth, contentHeight),
            Pair(middleWidth, contentHeight),
            Pair(rightWidth, contentHeight)
        )
    }
}
