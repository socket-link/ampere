package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.WatchViewState

/**
 * Renders the event stream view - a scrolling feed of events.
 *
 * This mode provides a real-time view of system activity, similar to
 * WatchCommand but in a modal interface. Events are color-coded by
 * significance and can be filtered with verbose mode.
 */
class EventStreamRenderer(
    private val terminal: Terminal,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) {

    fun render(viewState: WatchViewState, verboseMode: Boolean): String {
        return buildString {
            // Clear screen and move cursor to home
            append("\u001B[2J") // Clear screen
            append("\u001B[H")  // Move cursor to home

            // Header
            appendHeader(verboseMode)
            append("\n\n")

            // Get events to show (filter by verbose mode)
            val eventsToShow = if (verboseMode) {
                viewState.recentSignificantEvents
            } else {
                viewState.recentSignificantEvents.filter {
                    it.significance != EventSignificance.ROUTINE
                }
            }

            // Event stream
            val affinityByAgentName = viewState.agentStates.values.associate { state ->
                state.displayName to state.affinityName
            }
            appendEventStream(eventsToShow, affinityByAgentName)
            append("\n")

            // Footer with shortcuts
            appendFooter()
        }
    }

    private fun StringBuilder.appendHeader(verboseMode: Boolean) {
        append(terminal.render(bold(TextColors.cyan("Event Stream"))))
        if (verboseMode) {
            append(" ")
            append(terminal.render(TextColors.yellow("(verbose mode)")))
        } else {
            append(" ")
            append(terminal.render(dim("(significant events only)")))
        }
    }

    private fun StringBuilder.appendEventStream(
        events: List<link.socket.ampere.cli.watch.presentation.SignificantEventSummary>,
        affinityByAgentName: Map<String, String?>
    ) {
        if (events.isEmpty()) {
            append(terminal.render(dim("No events yet")))
            append("\n")
            return
        }

        // Calculate how many events to show based on terminal height
        // Reserve space for header (3 lines) + footer (2 lines) = 5 lines
        val availableHeight = (terminal.info.height - 5).coerceAtLeast(10)

        events.take(availableHeight).forEach { event ->
            appendEventLine(event, affinityByAgentName)
        }
    }

    private fun StringBuilder.appendEventLine(
        event: link.socket.ampere.cli.watch.presentation.SignificantEventSummary,
        affinityByAgentName: Map<String, String?>
    ) {
        val eventColor = when (event.significance) {
            EventSignificance.CRITICAL -> TextColors.red
            EventSignificance.SIGNIFICANT -> TextColors.white
            EventSignificance.ROUTINE -> TextColors.gray
        }

        val icon = when (event.significance) {
            EventSignificance.CRITICAL -> "🔴"
            EventSignificance.SIGNIFICANT -> "⚫"
            EventSignificance.ROUTINE -> "○"
        }

        val timeStr = formatTime(event.timestamp)
        val timeSinceStr = formatTimeSince(event.timestamp)

        append(terminal.render(dim(timeStr)))
        append(" ")
        append(icon)
        append(" ")
        append(terminal.render(eventColor(event.summaryText)))
        append(" ")
        append(terminal.render(dim("from ")))
        val affinityName = affinityByAgentName[event.sourceAgentName]
        val agentColor = affinityName?.let { SparkColors.forAffinityName(it) } ?: TextColors.gray
        append(terminal.render(agentColor(event.sourceAgentName)))
        append(terminal.render(dim(" ($timeSinceStr)")))
        append("\n")
    }

    private fun StringBuilder.appendFooter() {
        append(terminal.render(dim("d=dashboard  v=toggle verbose  h=help  Ctrl+C=exit")))
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
