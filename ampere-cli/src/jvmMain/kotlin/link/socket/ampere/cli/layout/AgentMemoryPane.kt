package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import link.socket.ampere.cli.animation.LightningAnimator
import link.socket.ampere.renderer.AmpereColors
import link.socket.ampere.repl.TerminalColors

/**
 * Narrow pane showing agent status and memory statistics.
 *
 * Designed for the right column (~20% width) of the 3-column layout.
 * Shows compact information about the current agent and memory operations.
 */
class AgentMemoryPane(
    private val terminal: Terminal
) : PaneRenderer {

    /**
     * Agent state for display.
     */
    enum class AgentDisplayState {
        IDLE,
        WORKING,
        THINKING,
        WAITING,
        IN_MEETING
    }

    /**
     * Current state to display.
     */
    data class AgentMemoryState(
        val agentName: String = "CodeWriter",
        val agentState: AgentDisplayState = AgentDisplayState.IDLE,
        val itemsRecalled: Int = 0,
        val itemsStored: Int = 0,
        val totalMemoryItems: Int = 0,  // Total items in memory
        val memoryCapacity: Int = 100,  // Max capacity for visualization
        val recentTags: List<String> = emptyList(),
        val currentPhase: String? = null,
        val recentActivity: List<MemoryActivity> = emptyList()  // Recent ops for sparkline
    )

    /**
     * Memory activity for sparkline visualization.
     */
    data class MemoryActivity(
        val type: MemoryOpType,
        val count: Int = 1
    )

    enum class MemoryOpType { RECALL, STORE }

    private var state = AgentMemoryState()
    private var frameCounter: Long = 0
    private val dischargeSequence = LightningAnimator.COMPACT_SEQUENCE

    fun updateState(newState: AgentMemoryState) {
        state = newState
    }

    override fun render(width: Int, height: Int): List<String> {
        frameCounter++
        val lines = mutableListOf<String>()

        // Header
        lines.add(terminal.render(bold("AGENT")))
        lines.add("")

        // Agent name and state
        val indicator = getStateIndicator(state.agentState)
        val stateColor = when (state.agentState) {
            AgentDisplayState.WORKING -> TextColors.green
            AgentDisplayState.THINKING -> TextColors.yellow
            AgentDisplayState.IDLE -> TextColors.gray
            AgentDisplayState.WAITING -> TextColors.yellow
            AgentDisplayState.IN_MEETING -> AmpereColors.accent
        }

        val agentNameTrunc = state.agentName.take(width - 4)
        lines.add(agentNameTrunc)
        lines.add("$indicator ${terminal.render(stateColor(state.agentState.name.lowercase()))}")
        lines.add("")

        // Current phase (always 3 lines for stability)
        lines.add(terminal.render(dim("Phase:")))
        lines.add(state.currentPhase?.take(width - 1) ?: terminal.render(dim("...")))
        lines.add("")

        // Memory section
        lines.add(terminal.render(bold("MEMORY")))
        lines.add("")

        // Memory usage bar
        val barWidth = (width - 2).coerceAtLeast(5)
        val usagePercent = if (state.memoryCapacity > 0) {
            (state.totalMemoryItems.toFloat() / state.memoryCapacity).coerceIn(0f, 1f)
        } else 0f
        lines.add(renderProgressBar(usagePercent, barWidth, "usage"))
        lines.add("")

        // Recalled with mini bar
        val recallBar = renderMiniBar(state.itemsRecalled, 10, barWidth - 10, AmpereColors.accent)
        lines.add("${terminal.render(AmpereColors.accent("< ${state.itemsRecalled.toString().padStart(2)}"))} $recallBar")

        // Stored with mini bar
        val storeBar = renderMiniBar(state.itemsStored, 10, barWidth - 10, TextColors.green)
        lines.add("${terminal.render(TextColors.green("> ${state.itemsStored.toString().padStart(2)}"))} $storeBar")
        lines.add("")

        // Activity sparkline
        if (state.recentActivity.isNotEmpty()) {
            lines.add(terminal.render(dim("Activity:")))
            lines.add(renderSparkline(state.recentActivity, width - 1))
            lines.add("")
        }

        // Recent tags
        if (state.recentTags.isNotEmpty()) {
            lines.add(terminal.render(dim("Tags:")))
            state.recentTags.take(3).forEach { tag ->
                lines.add(" ${tag.take(width - 2)}")
            }
        }

        // Pad to height
        while (lines.size < height) {
            lines.add("")
        }

        return lines.take(height).map { it.fitToWidth(width) }
    }

    /**
     * Render a progress bar with percentage.
     */
    private fun renderProgressBar(percent: Float, width: Int, label: String): String {
        val barInnerWidth = (width - 7).coerceAtLeast(3) // Leave room for [, ], and percent
        val filledWidth = (barInnerWidth * percent).toInt()
        val emptyWidth = barInnerWidth - filledWidth

        val filled = terminal.render(AmpereColors.accent("=".repeat(filledWidth)))
        val empty = terminal.render(dim("-".repeat(emptyWidth)))
        val pctStr = "${(percent * 100).toInt()}%".padStart(4)

        return "[$filled$empty]$pctStr"
    }

    /**
     * Render a mini horizontal bar.
     */
    private fun renderMiniBar(value: Int, maxValue: Int, width: Int, color: TextStyle): String {
        val barWidth = width.coerceAtLeast(3)
        val percent = if (maxValue > 0) (value.toFloat() / maxValue).coerceIn(0f, 1f) else 0f
        val filledWidth = (barWidth * percent).toInt()
        val emptyWidth = barWidth - filledWidth

        val filled = terminal.render(color("|".repeat(filledWidth)))
        val empty = terminal.render(dim(".".repeat(emptyWidth)))

        return "$filled$empty"
    }

    /**
     * Render a sparkline showing recent memory activity.
     */
    private fun renderSparkline(activity: List<MemoryActivity>, width: Int): String {
        val sparkChars = listOf(' ', '.', ':', '|')
        val recentOps = activity.takeLast(width)

        return recentOps.map { op ->
            val level = op.count.coerceIn(0, 3)
            val char = sparkChars[level]
            when (op.type) {
                MemoryOpType.RECALL -> terminal.render(AmpereColors.accent(char.toString()))
                MemoryOpType.STORE -> terminal.render(TextColors.green(char.toString()))
            }
        }.joinToString("")
    }

    private fun getStateIndicator(state: AgentDisplayState): String {
        return when (state) {
            AgentDisplayState.WORKING, AgentDisplayState.THINKING -> {
                val frame = dischargeSequence[(frameCounter % dischargeSequence.size).toInt()]
                "${frame.glow.toAnsi()}${frame.symbol}\u001B[0m"
            }
            AgentDisplayState.IDLE -> "\u001B[38;5;240m·\u001B[0m"
            AgentDisplayState.IN_MEETING -> "${TerminalColors.ACCENT}◆\u001B[0m"
            AgentDisplayState.WAITING -> "\u001B[38;5;226m◌\u001B[0m"
        }
    }
}
