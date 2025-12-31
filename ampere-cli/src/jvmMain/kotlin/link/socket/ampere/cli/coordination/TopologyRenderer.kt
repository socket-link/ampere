package link.socket.ampere.cli.coordination

import com.github.ajalt.mordant.rendering.TextColors
import link.socket.ampere.cli.layout.CharBuffer

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

