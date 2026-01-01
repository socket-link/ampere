package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.cli.watch.presentation.AgentActivityState
import link.socket.ampere.cli.watch.presentation.AgentState
import link.socket.ampere.cli.watch.presentation.CognitiveCluster
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.WatchViewState
import link.socket.ampere.renderer.AmpereColors

/**
 * Renders a focused view of a specific agent.
 *
 * This mode provides detailed information about a single agent,
 * including its current state, recent activity, and cognitive cycles.
 */
class AgentFocusRenderer(
    private val terminal: Terminal,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) {

    /**
     * Render the agent focus view.
     *
     * @param agentId The ID of the agent to focus on
     * @param viewState Current system view state
     * @param clusters Recent cognitive clusters
     * @param agentIndex The number index of this agent (1-9), or null
     * @return Rendered output, or null if agent not found
     */
    fun render(
        agentId: String?,
        viewState: WatchViewState,
        clusters: List<CognitiveCluster>,
        agentIndex: Int?
    ): String {
        // Handle null agent ID or agent not found
        if (agentId == null) {
            return renderNoAgentSelected()
        }

        val agentState = viewState.agentStates[agentId]
        if (agentState == null) {
            return renderAgentNotFound(agentId)
        }

        return buildString {
            // Clear screen and move cursor to home
            append("\u001B[2J") // Clear screen
            append("\u001B[H")  // Move cursor to home

            // Header with agent name and index
            appendHeader(agentState, agentIndex)
            append("\n\n")

            // Agent state section
            appendAgentState(agentState)
            append("\n")

            // Recent events from this agent
            appendRecentEvents(agentState, viewState)
            append("\n")

            // Cognitive cycles from this agent
            appendCognitiveCycles(agentState, clusters)
            append("\n")

            // Footer with shortcuts
            appendFooter()
        }
    }

    private fun StringBuilder.appendHeader(agentState: AgentActivityState, agentIndex: Int?) {
        append(terminal.render(bold(AmpereColors.accent("Agent Focus: ${agentState.displayName}"))))
        if (agentIndex != null) {
            append(terminal.render(dim(" (press $agentIndex to return here)")))
        }
    }

    private fun StringBuilder.appendAgentState(agentState: AgentActivityState) {
        append(terminal.render(bold("Current State")))
        append("\n")

        val stateColor = when (agentState.currentState) {
            AgentState.WORKING -> TextColors.green
            AgentState.THINKING -> TextColors.yellow
            AgentState.IDLE -> TextColors.gray
            AgentState.IN_MEETING -> TextColors.blue
            AgentState.WAITING -> TextColors.yellow
        }

        append("  Status: ")
        append(terminal.render(stateColor(agentState.currentState.displayText)))
        append("\n")

        if (agentState.consecutiveCognitiveCycles > 0) {
            append("  Cognitive cycles: ")
            append(terminal.render(TextColors.yellow(agentState.consecutiveCognitiveCycles.toString())))
            append("\n")
        }

        val timeSince = formatTimeSince(agentState.lastActivityTimestamp)
        append("  Last activity: $timeSince")
        append("\n")
    }

    private fun StringBuilder.appendRecentEvents(agentState: AgentActivityState, viewState: WatchViewState) {
        append(terminal.render(bold("Recent Activity")))
        append("\n")

        // Filter events from this agent
        val agentEvents = viewState.recentSignificantEvents
            .filter { it.sourceAgentName == agentState.displayName }
            .take(8)

        if (agentEvents.isEmpty()) {
            append(terminal.render(dim("  No recent events")))
            append("\n")
            return
        }

        agentEvents.forEach { event ->
            val eventColor = when (event.significance) {
                EventSignificance.CRITICAL -> TextColors.red
                EventSignificance.SIGNIFICANT -> TextColors.white
                EventSignificance.ROUTINE -> TextColors.gray
            }

            val timeStr = formatTime(event.timestamp)
            append("  ")
            append(terminal.render(dim(timeStr)))
            append(" ")
            append(terminal.render(eventColor(event.summaryText)))
            append("\n")
        }
    }

    private fun StringBuilder.appendCognitiveCycles(agentState: AgentActivityState, clusters: List<CognitiveCluster>) {
        append(terminal.render(bold("Cognitive Cycles")))
        append("\n")

        // Filter clusters from this agent
        val agentClusters = clusters
            .filter { it.agentId == agentState.agentId }
            .take(5)

        if (agentClusters.isEmpty()) {
            append(terminal.render(dim("  No cognitive cycles yet")))
            append("\n")
            return
        }

        agentClusters.forEach { cluster ->
            val timeStr = formatTime(cluster.startTimestamp)
            val durationStr = formatDuration(cluster.durationMillis)

            append("  ")
            append(terminal.render(dim(timeStr)))
            append(" ")
            append(terminal.render(TextColors.cyan(cluster.cycleType.name.lowercase().replace('_', ' '))))
            append(" ")
            append(terminal.render(dim("(${cluster.events.size} ops, $durationStr)")))
            append("\n")
        }
    }

    private fun StringBuilder.appendFooter() {
        append(terminal.render(dim("d=dashboard  1-9=other agents  h=help  Ctrl+C=exit")))
    }

    private fun renderNoAgentSelected(): String {
        return buildString {
            append("\u001B[2J\u001B[H")
            append(terminal.render(bold(TextColors.yellow("Agent Focus Mode"))))
            append("\n\n")
            append("No agent selected.")
            append("\n\n")
            append(terminal.render(dim("Press 1-9 to focus on an active agent, or 'd' to return to dashboard")))
        }
    }

    private fun renderAgentNotFound(agentId: String): String {
        return buildString {
            append("\u001B[2J\u001B[H")
            append(terminal.render(bold(TextColors.yellow("Agent No Longer Active"))))
            append("\n\n")
            append("Agent $agentId is no longer active.")
            append("\n\n")
            append(terminal.render(dim("Press 'd' to return to dashboard")))
        }
    }

    private fun formatTime(timestamp: kotlinx.datetime.Instant): String {
        val localDateTime = timestamp.toLocalDateTime(timeZone)
        return buildString {
            append(localDateTime.hour.toString().padStart(2, '0'))
            append(":")
            append(localDateTime.minute.toString().padStart(2, '0'))
            append(":")
            append(localDateTime.second.toString().padStart(2, '0'))
        }
    }

    private fun formatTimeSince(timestamp: kotlinx.datetime.Instant): String {
        val elapsed = clock.now().toEpochMilliseconds() - timestamp.toEpochMilliseconds()
        return when {
            elapsed < 1000 -> "just now"
            elapsed < 60_000 -> "${elapsed / 1000}s ago"
            elapsed < 3600_000 -> "${elapsed / 60_000}m ago"
            elapsed < 86400_000 -> "${elapsed / 3600_000}h ago"
            else -> "${elapsed / 86400_000}d ago"
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        return when {
            milliseconds < 1000 -> "${milliseconds}ms"
            milliseconds < 60_000 -> "${milliseconds / 1000}s"
            milliseconds < 3600_000 -> "${milliseconds / 60_000}m"
            else -> "${milliseconds / 3600_000}h"
        }
    }
}
