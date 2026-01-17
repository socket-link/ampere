package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.cli.watch.presentation.AgentState
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.SystemState
import link.socket.ampere.cli.watch.presentation.WatchViewState

/**
 * Renders the dashboard view using Mordant's terminal control.
 *
 * Think of this as the visual cortex - it takes processed sensory
 * information and creates a coherent visual representation.
 */
class DashboardRenderer(
    private val terminal: Terminal,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) {
    // Spinner characters cycle
    private val spinners = arrayOf("◐", "◓", "◑", "◒")

    fun render(viewState: WatchViewState, verboseMode: Boolean = false): String {
        return buildString {
            // Clear screen and move cursor to home
            append("\u001B[2J") // Clear screen
            append("\u001B[H")  // Move cursor to home

            // System vitals header
            appendSystemVitals(viewState.systemVitals, verboseMode)
            append("\n\n")

            // Agent activity panel
            appendAgentActivity(viewState.agentStates)
            append("\n")

            // Recent significant events
            val affinityByAgentName = viewState.agentStates.values.associate { state ->
                state.displayName to state.affinityName
            }
            appendRecentEvents(viewState.recentSignificantEvents, affinityByAgentName)
            append("\n")

            // Footer with keyboard shortcuts
            appendFooter()
        }
    }

    private fun StringBuilder.appendSystemVitals(vitals: link.socket.ampere.cli.watch.presentation.SystemVitals, verboseMode: Boolean) {
        val stateColor = when (vitals.systemState) {
            SystemState.IDLE -> TextColors.green
            SystemState.WORKING -> TextColors.blue
            SystemState.ATTENTION_NEEDED -> TextColors.red
        }

        val lastEventText = vitals.lastSignificantEventTime?.let { timestamp ->
            val elapsed = clock.now().toEpochMilliseconds() - timestamp.toEpochMilliseconds()
            formatDuration(elapsed)
        } ?: "never"

        append(terminal.render(bold(TextColors.cyan("AMPERE Dashboard"))))
        if (verboseMode) {
            append(" ")
            append(terminal.render(TextColors.yellow("(verbose)")))
        }
        append(" • ")
        append("${vitals.activeAgentCount} agents active")
        append(" • ")
        append(terminal.render(stateColor(vitals.systemState.name.lowercase())))
        append(" • ")
        append("last event: $lastEventText")
    }

    private fun StringBuilder.appendAgentActivity(states: Map<String, link.socket.ampere.cli.watch.presentation.AgentActivityState>) {
        append(terminal.render(bold("Agent Activity")))
        append("\n")

        if (states.isEmpty()) {
            append(terminal.render(dim("No agents running")))
            append("\n")
            return
        }

        states.values.sortedBy { it.displayName }.forEach { state ->
            val spinner = spinners[state.consecutiveCognitiveCycles % 4]
            val stateColor = when (state.currentState) {
                AgentState.WORKING -> TextColors.green
                AgentState.THINKING -> TextColors.yellow
                AgentState.IDLE -> TextColors.gray
                AgentState.IN_MEETING -> TextColors.blue
                AgentState.WAITING -> TextColors.yellow
            }

            val cycleInfo = if (state.consecutiveCognitiveCycles > 0) {
                " (${state.consecutiveCognitiveCycles} cycles)"
            } else ""

            val name = state.displayName.take(25).padEnd(25)
            val nameStyle = state.affinityName?.let { SparkColors.forAffinityName(it) } ?: TextColors.white
            val depthIndicator = if (state.sparkDepth > 0) {
                " ${SparkColors.renderDepthIndicator(state.sparkDepth, SparkColors.DepthDisplayStyle.DOTS)}"
            } else ""

            append("$spinner ${terminal.render(nameStyle(name))} ")
            append(terminal.render(stateColor(state.currentState.displayText)))
            append(terminal.render(dim(cycleInfo + depthIndicator)))
            append("\n")
        }
    }

    private fun StringBuilder.appendRecentEvents(
        events: List<link.socket.ampere.cli.watch.presentation.SignificantEventSummary>,
        affinityByAgentName: Map<String, String?>
    ) {
        append(terminal.render(bold("Recent Events")))
        append("\n")

        if (events.isEmpty()) {
            append(terminal.render(dim("No recent activity")))
            append("\n")
            return
        }

        events.take(10).forEach { event ->
            val eventColor = when (event.significance) {
                EventSignificance.CRITICAL -> TextColors.red
                EventSignificance.SIGNIFICANT -> TextColors.white
                EventSignificance.ROUTINE -> TextColors.gray
            }

            val timeStr = formatTime(event.timestamp)
            append(terminal.render(dim(timeStr)))
            append(" ")
            append(terminal.render(eventColor(event.summaryText)))
            append(" ")
            append(terminal.render(dim("from ")))
            val affinityName = affinityByAgentName[event.sourceAgentName]
            val agentColor = affinityName?.let { SparkColors.forAffinityName(it) } ?: TextColors.gray
            append(terminal.render(agentColor(event.sourceAgentName)))
            append("\n")
        }
    }

    private fun StringBuilder.appendFooter() {
        append("\n")
        append(terminal.render(dim("Ctrl+C to stop • Updates every second")))
    }

    private fun formatDuration(milliseconds: Long): String {
        return when {
            milliseconds < 1000 -> "just now"
            milliseconds < 60_000 -> "${milliseconds / 1000}s ago"
            milliseconds < 3600_000 -> "${milliseconds / 60_000}m ago"
            milliseconds < 86400_000 -> "${milliseconds / 3600_000}h ago"
            else -> "${milliseconds / 86400_000}d ago"
        }
    }

    private fun formatTime(timestamp: Instant): String {
        val localDateTime = timestamp.toLocalDateTime(timeZone)
        return buildString {
            append(localDateTime.hour.toString().padStart(2, '0'))
            append(":")
            append(localDateTime.minute.toString().padStart(2, '0'))
            append(":")
            append(localDateTime.second.toString().padStart(2, '0'))
        }
    }
}
