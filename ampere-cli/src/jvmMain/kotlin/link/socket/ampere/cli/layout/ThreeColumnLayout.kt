package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal

/**
 * A layout manager that arranges three panes in columns with a status bar.
 *
 * Layout structure:
 * ┌──────────────────────┬──────────────────────────┬─────────────────────┐
 * │   LEFT PANE (35%)    │    MIDDLE PANE (40%)     │   RIGHT PANE (25%)  │
 * │   Event stream       │    Main interaction      │   Agent/Memory      │
 * ├──────────────────────┴────────────────────────────────┴─────────────────┤
 * │ Status bar with shortcuts and system status                             │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
class ThreeColumnLayout(
    private val terminal: Terminal,
    private val leftRatio: Float = 0.35f,
    private val middleRatio: Float = 0.40f,
    private val rightRatio: Float = 0.25f
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

        // Calculate column positions for dividers
        val divider1Col = leftWidth + 1
        val divider2Col = leftWidth + 1 + middleWidth + 1

        return buildString {
            // Use explicit cursor positioning for each line to prevent flickering
            // Row 1 is the first line

            // Render content rows
            for (i in 0 until contentHeight) {
                val row = i + 1
                val left = leftLines.getOrElse(i) { " ".repeat(leftWidth) }
                val middle = middleLines.getOrElse(i) { " ".repeat(middleWidth) }
                val right = rightLines.getOrElse(i) { " ".repeat(rightWidth) }

                // Position cursor at start of row, write left pane
                append("\u001B[${row};1H")
                append(left)

                // Position cursor at first divider column
                append("\u001B[${row};${divider1Col}H")
                append(dividerChar)

                // Write middle pane content (cursor is now after divider)
                append(middle)

                // Position cursor at second divider column
                append("\u001B[${row};${divider2Col}H")
                append(dividerChar)

                // Write right pane and clear rest of line
                append(right)
                append("\u001B[K")
            }

            // Horizontal divider above status bar
            val dividerRow = contentHeight + 1
            append("\u001B[${dividerRow};1H")
            append(horizontalDivider.repeat(totalWidth))
            append("\u001B[K")

            // Status bar
            val statusRow = contentHeight + 2
            append("\u001B[${statusRow};1H")
            append(statusBar.fitToWidth(totalWidth))
            append("\u001B[K")
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
