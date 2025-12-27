package link.socket.ampere.cli.coordination

import com.github.ajalt.mordant.rendering.TextColors

/**
 * Renders topology layouts as ASCII art network graphs.
 *
 * This renderer creates visual representations of agent coordination using:
 * - ASCII boxes for agent nodes
 * - Lines showing connections between agents
 * - Color coding for agent states
 * - Animated spinners for activity
 */
class TopologyRenderer {

    companion object {
        private val SPINNER_CHARS = listOf('◐', '◓', '◑', '◒')
        private const val EMPTY_MESSAGE = "No agent interactions detected yet.\nAgents will appear here as they coordinate."
    }

    /**
     * Render a topology layout to an ASCII string.
     *
     * @param layout The topology layout to render
     * @param tick Animation tick counter for spinner rotation
     * @return Rendered ASCII art string
     */
    fun render(layout: TopologyLayout, tick: Int = 0): String {
        if (layout.nodes.isEmpty()) {
            return EMPTY_MESSAGE
        }

        val buffer = CharBuffer(layout.width, layout.height + 10) // Extra space for legend

        // Draw all nodes
        layout.nodes.forEach { node ->
            renderNode(buffer, node, tick)
        }

        // Draw all edges
        layout.edges.forEach { edge ->
            renderEdge(buffer, edge, layout.nodes)
        }

        // Add legend
        renderLegend(buffer, layout.height + 2)

        return buffer.toString()
    }

    /**
     * Render a single agent node as an ASCII box.
     */
    private fun renderNode(buffer: CharBuffer, node: TopologyNode, tick: Int) {
        val x = node.position.x
        val y = node.position.y

        val spinner = SPINNER_CHARS[tick % SPINNER_CHARS.size]
        val stateText = node.state.toString().lowercase()
        val color = getStateColor(node.state)

        // Top border
        buffer.write(x, y, "┌─────────────┐", color)

        // Name line
        val nameLine = "│ ${node.displayName.take(11).padEnd(11)} │"
        buffer.write(x, y + 1, nameLine, color)

        // State line with spinner
        val stateLine = "│  $spinner $stateText${" ".repeat(11 - stateText.length - 3)} │"
        buffer.write(x, y + 2, stateLine, color)

        // Bottom border
        buffer.write(x, y + 3, "└─────────────┘", color)
    }

    /**
     * Render an edge connecting two nodes.
     */
    private fun renderEdge(
        buffer: CharBuffer,
        edge: TopologyEdge,
        nodes: List<TopologyNode>,
    ) {
        val sourceNode = nodes.find { it.agentId == edge.source } ?: return
        val targetNode = nodes.find { it.agentId == edge.target } ?: return

        // Calculate connection points (right side of source, left side of target)
        val sourceX = sourceNode.position.x + 15 // End of source box
        val sourceY = sourceNode.position.y + 1 // Middle of box
        val targetX = targetNode.position.x // Start of target box
        val targetY = targetNode.position.y + 1

        // Simple horizontal line for now
        if (sourceY == targetY && sourceX < targetX) {
            // Horizontal edge
            val lineChar = if (edge.isActive) '─' else '╌'
            val lineLength = targetX - sourceX

            // Draw line
            for (i in 0 until lineLength) {
                buffer.write(sourceX + i, sourceY, lineChar.toString())
            }

            // Draw arrow
            buffer.write(targetX - 1, targetY, "▶")

            // Draw label above line
            val labelX = sourceX + (lineLength / 2) - (edge.label.length / 2)
            buffer.write(labelX, sourceY - 1, edge.label, TextColors.yellow)
        }
    }

    /**
     * Render the legend explaining line styles.
     */
    private fun renderLegend(buffer: CharBuffer, startY: Int) {
        buffer.write(0, startY, "───  active (< 60s)   ╌╌╌  recent (< 5m)   dim: historical")
    }

    /**
     * Get the color for an agent state.
     */
    private fun getStateColor(state: NodeState): TextColors {
        return when (state) {
            NodeState.ACTIVE -> TextColors.green
            NodeState.BLOCKED -> TextColors.red
            NodeState.IDLE -> TextColors.white
            NodeState.OFFLINE -> TextColors.gray
        }
    }
}

/**
 * A 2D character buffer for composing ASCII art.
 *
 * This buffer allows writing text at specific coordinates and
 * handles overlapping writes by keeping the last written character.
 *
 * @property width Width of the buffer in characters
 * @property height Height of the buffer in lines
 */
class CharBuffer(
    private val width: Int,
    private val height: Int,
) {
    private data class Cell(
        val char: Char,
        val color: TextColors? = null,
    )

    private val buffer: Array<Array<Cell?>> = Array(height) { arrayOfNulls(width) }

    /**
     * Write text at the specified position.
     *
     * @param x Horizontal position (column)
     * @param y Vertical position (row)
     * @param text Text to write
     * @param color Optional color for the text
     */
    fun write(x: Int, y: Int, text: String, color: TextColors? = null) {
        if (y < 0 || y >= height) return

        text.forEachIndexed { index, char ->
            val writeX = x + index
            if (writeX >= 0 && writeX < width) {
                buffer[y][writeX] = Cell(char, color)
            }
        }
    }

    /**
     * Convert the buffer to a string representation.
     *
     * @return Multi-line string with the buffer contents
     */
    override fun toString(): String {
        return buffer.joinToString("\n") { row ->
            buildString {
                var currentColor: TextColors? = null

                row.forEach { cell ->
                    if (cell != null) {
                        // Apply color if it changed
                        if (cell.color != currentColor) {
                            currentColor = cell.color
                            if (cell.color != null) {
                                // Note: In real usage, you'd apply ANSI color codes here
                                // For now, just append the character
                            }
                        }
                        append(cell.char)
                    } else {
                        append(' ')
                    }
                }
            }.trimEnd()
        }.trimEnd()
    }
}
