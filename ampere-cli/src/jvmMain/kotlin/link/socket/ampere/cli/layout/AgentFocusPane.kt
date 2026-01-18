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
import link.socket.ampere.cli.watch.presentation.SparkTransition
import link.socket.ampere.cli.watch.presentation.SparkTransitionDirection
import link.socket.ampere.renderer.SparkColors
import link.socket.ampere.renderer.SparkNameFormatter

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
        val cognitiveClusters: List<CognitiveCluster> = emptyList(),
        /** History of Spark transitions for this agent. */
        val sparkHistory: List<SparkTransition> = emptyList()
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

        // Cognitive Context (Spark stack) section - only if there's cognitive state
        if (agentState.affinityName != null || agentState.sparkNames.isNotEmpty()) {
            lines.addAll(renderCognitiveContext(agentState, width))
            lines.add("")
        }

        // Spark History section - only if there's history
        if (state.sparkHistory.isNotEmpty()) {
            lines.addAll(renderSparkHistory(width, (height - lines.size - 10).coerceAtLeast(3)))
            lines.add("")
        }

        // Recent activity sub-header
        lines.addAll(renderSubHeader("Activity", width, terminal))

        // Recent events from this agent
        lines.addAll(renderRecentEvents(width, height - lines.size - 3))

        // Pad to height (reserve space for footer)
        while (lines.size < height - 1) {
            lines.add("")
        }

        // Footer hint
        lines.add(terminal.render(dim("[ESC] close  :sparks for details")))

        return lines.take(height).map { it.fitToWidth(width) }
    }

    private fun renderDrawerHeader(
        agentState: AgentActivityState,
        agentIndex: Int?,
        width: Int
    ): List<String> {
        val lines = mutableListOf<String>()

        // Drawer title with affinity-based color
        val affinityColor = agentState.affinityName?.let { SparkColors.forAffinityName(it) } ?: TextColors.cyan
        val title = "Agent: ${agentState.displayName.take(width - 12)}"

        // Add cognitive depth indicator if available
        val depthIndicator = if (agentState.sparkDepth > 0) {
            " ${SparkColors.renderDepthIndicator(agentState.sparkDepth, SparkColors.DepthDisplayStyle.ARROWS)}"
        } else ""

        lines.add(terminal.render(bold(affinityColor(title))) + terminal.render(dim(depthIndicator)))

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

    /**
     * Render the cognitive context (Spark stack) section.
     */
    private fun renderCognitiveContext(agentState: AgentActivityState, width: Int): List<String> {
        val lines = mutableListOf<String>()

        // Sub-header
        lines.addAll(renderSubHeader("Cognitive Context", width, terminal))

        // Affinity as root element
        val affinityName = agentState.affinityName ?: "UNKNOWN"
        val affinityColor = SparkColors.forAffinityName(affinityName)
        lines.add(terminal.render(affinityColor("${SparkColors.SparkIcons.STACK_ROOT} $affinityName")))

        // Spark layers with tree characters
        agentState.sparkNames.forEachIndexed { index, sparkName ->
            val isLast = index == agentState.sparkNames.lastIndex
            val prefix = if (isLast) SparkColors.SparkIcons.STACK_LAST else SparkColors.SparkIcons.STACK_BRANCH
            val activeMarker = if (isLast) " ${terminal.render(TextColors.cyan("← active"))}" else ""

            // Truncate spark name to fit width
            val maxSparkNameLength = width - prefix.length - 12
            val displayName = SparkNameFormatter.format(sparkName).take(maxSparkNameLength)

            lines.add("$prefix $displayName$activeMarker")
        }

        // Show message if no sparks
        if (agentState.sparkNames.isEmpty()) {
            lines.add(terminal.render(dim("   (no specialization)")))
        }

        return lines
    }

    /**
     * Render the Spark transition history section.
     */
    private fun renderSparkHistory(width: Int, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()

        // Sub-header
        lines.addAll(renderSubHeader("Spark History", width, terminal))

        if (state.sparkHistory.isEmpty()) {
            lines.add(terminal.render(dim("No transitions recorded")))
            return lines
        }

        // Show recent transitions (most recent first)
        state.sparkHistory.reversed().take(maxLines).forEach { transition ->
            val time = formatTime(transition.timestamp)
            val icon = when (transition.direction) {
                SparkTransitionDirection.APPLIED -> SparkColors.SparkIcons.APPLIED
                SparkTransitionDirection.REMOVED -> SparkColors.SparkIcons.REMOVED
            }
            val prefix = when (transition.direction) {
                SparkTransitionDirection.APPLIED -> "+"
                SparkTransitionDirection.REMOVED -> "-"
            }
            val color = when (transition.direction) {
                SparkTransitionDirection.APPLIED -> TextColors.cyan
                SparkTransitionDirection.REMOVED -> TextColors.gray
            }

            val maxNameLength = width - 12
            val name = SparkNameFormatter.format(transition.sparkName).take(maxNameLength)
            lines.add("${terminal.render(dim(time))} $icon ${terminal.render(color("$prefix$name"))}")
        }

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
