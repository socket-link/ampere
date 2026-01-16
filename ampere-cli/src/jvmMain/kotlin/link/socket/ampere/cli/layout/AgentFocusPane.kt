package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.cli.watch.presentation.AgentActivityState
import link.socket.ampere.cli.watch.presentation.AgentState
import link.socket.ampere.cli.watch.presentation.CognitiveCluster
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.SignificantEventSummary

/**
 * Agent focus as a drawer-style pane that can be rendered within the layout.
 *
 * Unlike the full-screen AgentFocusRenderer, this pane renders agent details
 * in a constrained area that can be composed with other panes.
 *
 * Layout when agent focus is active:
 * ┌────────────────────────────────────────┬────────────────────┐
 * │   LEFT + MIDDLE (combined 75%)         │   AGENT FOCUS (25%)│
 * │   Event stream + Cognitive cycle       │   Agent: CodeWriter│
 * │                                        │   ───────────────  │
 * │                                        │   Status: WORKING  │
 * │                                        │   Cycles: 3        │
 * │                                        │   ─── Activity ──  │
 * │                                        │   ...events...     │
 * └────────────────────────────────────────┴────────────────────┘
 */
class AgentFocusPane(
    private val terminal: Terminal,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : PaneRenderer {

    /**
     * Data needed to render the agent focus.
     */
    data class FocusState(
        val agentId: String? = null,
        val agentIndex: Int? = null,
        val agentState: AgentActivityState? = null,
        val recentEvents: List<SignificantEventSummary> = emptyList(),
        val cognitiveClusters: List<CognitiveCluster> = emptyList()
    )

    private var state = FocusState()

    fun updateState(newState: FocusState) {
        state = newState
    }

    override fun render(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()

        // Check if we have an agent to show
        if (state.agentId == null || state.agentState == null) {
            return renderNoAgentSelected(width, height)
        }

        val agentState = state.agentState!!

        // Header with agent name and close hint
        lines.addAll(renderDrawerHeader(agentState, state.agentIndex, width))

        // Agent state section
        lines.addAll(renderAgentState(agentState, width))

        // Separator
        lines.add("")

        // Recent activity sub-header
        lines.addAll(renderSubHeader("Activity", width, terminal))

        // Recent events from this agent
        lines.addAll(renderRecentEvents(width, height - lines.size - 3))

        // Pad to height (reserve space for footer)
        while (lines.size < height - 1) {
            lines.add("")
        }

        // Footer hint
        lines.add(terminal.render(dim("[ESC] close")))

        return lines.take(height).map { it.fitToWidth(width) }
    }

    private fun renderDrawerHeader(
        agentState: AgentActivityState,
        agentIndex: Int?,
        width: Int
    ): List<String> {
        val lines = mutableListOf<String>()

        // Drawer title with close button hint
        val stateIndicator = getStateIndicator(agentState.currentState)
        val title = "Agent: ${agentState.displayName.take(width - 12)}"
        lines.add(terminal.render(bold(TextColors.cyan(title))))

        // Separator
        lines.add(terminal.render(dim("─".repeat(width))))

        // Agent index hint
        if (agentIndex != null) {
            lines.add(terminal.render(dim("press $agentIndex to return")))
        }
        lines.add("")

        return lines
    }

    private fun renderAgentState(agentState: AgentActivityState, width: Int): List<String> {
        val lines = mutableListOf<String>()

        val stateColor = when (agentState.currentState) {
            AgentState.WORKING -> TextColors.green
            AgentState.THINKING -> TextColors.yellow
            AgentState.IDLE -> TextColors.gray
            AgentState.IN_MEETING -> TextColors.blue
            AgentState.WAITING -> TextColors.yellow
        }

        val stateIndicator = getStateIndicator(agentState.currentState)
        lines.add("$stateIndicator ${terminal.render(stateColor(agentState.currentState.displayText))}")

        if (agentState.consecutiveCognitiveCycles > 0) {
            lines.add("${terminal.render(dim("Cycles:"))} ${agentState.consecutiveCognitiveCycles}")
        }

        val timeSince = formatTimeSince(agentState.lastActivityTimestamp)
        lines.add("${terminal.render(dim("Last:"))} $timeSince")

        return lines
    }

    private fun renderRecentEvents(width: Int, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()

        // Filter events from this agent
        val agentEvents = state.recentEvents
            .filter { it.sourceAgentName == state.agentState?.displayName }
            .take(maxLines.coerceAtMost(8))

        if (agentEvents.isEmpty()) {
            lines.add(terminal.render(dim("No recent events")))
            return lines
        }

        agentEvents.forEach { event ->
            if (lines.size >= maxLines) return lines

            val eventColor = when (event.significance) {
                EventSignificance.CRITICAL -> TextColors.red
                EventSignificance.SIGNIFICANT -> TextColors.white
                EventSignificance.ROUTINE -> TextColors.gray
            }

            val timeStr = formatTime(event.timestamp)
            val summary = IdFormatter.truncateUuidsInText(event.summaryText).take(width - 7)
            lines.add("${terminal.render(dim(timeStr))} ${terminal.render(eventColor(summary))}")
        }

        return lines
    }

    private fun renderNoAgentSelected(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()

        // Header
        lines.addAll(renderSectionHeader("Agent Focus", width, terminal))

        // No agent message
        lines.add("")
        lines.add(terminal.render(dim("No agent selected")))
        lines.add("")
        lines.add(terminal.render(dim("Press 1-9 to focus")))
        lines.add(terminal.render(dim("on an active agent")))

        // Pad to height
        while (lines.size < height) {
            lines.add("")
        }

        return lines.take(height).map { it.fitToWidth(width) }
    }

    private fun getStateIndicator(state: AgentState): String {
        return when (state) {
            AgentState.WORKING -> terminal.render(TextColors.green("●"))
            AgentState.THINKING -> terminal.render(TextColors.yellow("◐"))
            AgentState.IDLE -> terminal.render(dim("○"))
            AgentState.IN_MEETING -> terminal.render(TextColors.blue("◆"))
            AgentState.WAITING -> terminal.render(TextColors.yellow("◌"))
        }
    }

    private fun formatTime(timestamp: Instant): String {
        val local = timestamp.toLocalDateTime(timeZone)
        return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    }

    private fun formatTimeSince(timestamp: Instant): String {
        val elapsed = clock.now().toEpochMilliseconds() - timestamp.toEpochMilliseconds()
        return when {
            elapsed < 1000 -> "just now"
            elapsed < 60_000 -> "${elapsed / 1000}s ago"
            elapsed < 3600_000 -> "${elapsed / 60_000}m ago"
            elapsed < 86400_000 -> "${elapsed / 3600_000}h ago"
            else -> "${elapsed / 86400_000}d ago"
        }
    }
}
