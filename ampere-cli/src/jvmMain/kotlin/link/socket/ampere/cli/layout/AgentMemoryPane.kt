package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import link.socket.ampere.cli.animation.LightningAnimator

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
        val recentTags: List<String> = emptyList(),
        val currentPhase: String? = null
    )

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
            AgentDisplayState.IN_MEETING -> TextColors.blue
        }

        val agentNameTrunc = state.agentName.take(width - 4)
        lines.add(agentNameTrunc)
        lines.add("$indicator ${terminal.render(stateColor(state.agentState.name.lowercase()))}")
        lines.add("")

        // Current phase if active
        val phase = state.currentPhase
        if (phase != null) {
            lines.add(terminal.render(dim("Phase:")))
            lines.add(phase.take(width - 1))
            lines.add("")
        }

        // Memory section
        lines.add(terminal.render(bold("MEMORY")))
        lines.add("")

        // Recalled/stored counts
        val recalledStr = "${state.itemsRecalled} recalled"
        val storedStr = "${state.itemsStored} stored"
        lines.add(terminal.render(TextColors.cyan(recalledStr.take(width - 1))))
        lines.add(terminal.render(TextColors.green(storedStr.take(width - 1))))
        lines.add("")

        // Recent tags
        if (state.recentTags.isNotEmpty()) {
            lines.add(terminal.render(dim("Tags:")))
            state.recentTags.take(5).forEach { tag ->
                lines.add(" ${tag.take(width - 2)}")
            }
        }

        // Pad to height
        while (lines.size < height) {
            lines.add("")
        }

        return lines.take(height).map { it.fitToWidth(width) }
    }

    private fun getStateIndicator(state: AgentDisplayState): String {
        return when (state) {
            AgentDisplayState.WORKING, AgentDisplayState.THINKING -> {
                val frame = dischargeSequence[(frameCounter % dischargeSequence.size).toInt()]
                "${frame.glow.toAnsi()}${frame.symbol}\u001B[0m"
            }
            AgentDisplayState.IDLE -> "\u001B[38;5;240m·\u001B[0m"
            AgentDisplayState.IN_MEETING -> "\u001B[38;5;33m◆\u001B[0m"
            AgentDisplayState.WAITING -> "\u001B[38;5;226m◌\u001B[0m"
        }
    }
}
