package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.cli.animation.LightningAnimator
import link.socket.ampere.cli.watch.presentation.AgentState
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.SystemState
import link.socket.ampere.cli.watch.presentation.WatchViewState

/**
 * Adapter that renders dashboard content as a pane.
 *
 * This renders a condensed version of the dashboard suitable for
 * display in a split-pane layout.
 */
class DashboardPaneAdapter(
    private val terminal: Terminal,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : PaneRenderer {

    private var frameCounter: Long = 0
    private val dischargeSequence = LightningAnimator.COMPACT_SEQUENCE

    private var viewState: WatchViewState? = null
    private var verboseMode: Boolean = false

    fun updateState(viewState: WatchViewState, verboseMode: Boolean = false) {
        this.viewState = viewState
        this.verboseMode = verboseMode
    }

    override fun render(width: Int, height: Int): List<String> {
        frameCounter++
        val state = viewState ?: return emptyPaneContent(width, height, "No data")

        val lines = mutableListOf<String>()

        // Header
        lines.add(renderHeader(state, width))
        lines.add("")

        // Agent activity section
        lines.add(terminal.render(bold("Agent Activity")))

        if (state.agentStates.isEmpty()) {
            lines.add(terminal.render(dim("No agents running")))
        } else {
            val maxAgents = (height - 8).coerceAtLeast(3)
            state.agentStates.values.sortedBy { it.displayName }.take(maxAgents).forEachIndexed { index, agentState ->
                lines.add(renderAgentLine(agentState, index, width))
            }
        }

        lines.add("")

        // Recent events section
        lines.add(terminal.render(bold("Recent Events")))

        val eventsHeight = height - lines.size - 2
        if (state.recentSignificantEvents.isEmpty()) {
            lines.add(terminal.render(dim("No recent activity")))
        } else {
            state.recentSignificantEvents.take(eventsHeight.coerceAtLeast(1)).forEach { event ->
                lines.add(renderEventLine(event, width))
            }
        }

        // Pad to fill height
        while (lines.size < height - 1) {
            lines.add("")
        }

        // Footer
        lines.add(terminal.render(dim("Ctrl+C to exit")))

        return lines.map { it.fitToWidth(width) }
    }

    private fun renderHeader(state: WatchViewState, width: Int): String {
        val stateColor = when (state.systemVitals.systemState) {
            SystemState.IDLE -> TextColors.green
            SystemState.WORKING -> TextColors.blue
            SystemState.ATTENTION_NEEDED -> TextColors.red
        }

        return buildString {
            append(terminal.render(bold(TextColors.cyan("AMPERE"))))
            append(" ${state.systemVitals.activeAgentCount} active")
            append(" ")
            append(terminal.render(stateColor(state.systemVitals.systemState.name.lowercase())))
        }
    }

    private fun renderAgentLine(state: link.socket.ampere.cli.watch.presentation.AgentActivityState, index: Int, width: Int): String {
        val indicator = getAgentIndicator(state.currentState, index)
        val stateColor = when (state.currentState) {
            AgentState.WORKING -> TextColors.green
            AgentState.THINKING -> TextColors.yellow
            AgentState.IDLE -> TextColors.gray
            AgentState.IN_MEETING -> TextColors.blue
            AgentState.WAITING -> TextColors.yellow
        }

        val maxNameLen = (width - 20).coerceAtLeast(10)
        val name = state.displayName.take(maxNameLen).padEnd(maxNameLen)

        return "$indicator $name ${terminal.render(stateColor(state.currentState.displayText))}"
    }

    private fun getAgentIndicator(state: AgentState, agentIndex: Int): String {
        return when (state) {
            AgentState.WORKING, AgentState.THINKING -> {
                val offsetFrame = (frameCounter + agentIndex * 2) % dischargeSequence.size
                val frame = dischargeSequence[offsetFrame.toInt()]
                "${frame.glow.toAnsi()}${frame.symbol}\u001B[0m"
            }
            AgentState.IDLE -> "\u001B[38;5;240m·\u001B[0m"
            AgentState.IN_MEETING -> "\u001B[38;5;33m◆\u001B[0m"
            AgentState.WAITING -> "\u001B[38;5;226m◌\u001B[0m"
        }
    }

    private fun renderEventLine(event: link.socket.ampere.cli.watch.presentation.SignificantEventSummary, width: Int): String {
        val eventColor = when (event.significance) {
            EventSignificance.CRITICAL -> TextColors.red
            EventSignificance.SIGNIFICANT -> TextColors.white
            EventSignificance.ROUTINE -> TextColors.gray
        }

        val timeStr = formatTime(event.timestamp)
        val maxTextLen = (width - 15).coerceAtLeast(10)
        val text = event.summaryText.take(maxTextLen)

        return "${terminal.render(dim(timeStr))} ${terminal.render(eventColor(text))}"
    }

    private fun formatTime(timestamp: Instant): String {
        val localDateTime = timestamp.toLocalDateTime(timeZone)
        return "${localDateTime.hour.toString().padStart(2, '0')}:${localDateTime.minute.toString().padStart(2, '0')}"
    }

    private fun emptyPaneContent(width: Int, height: Int, message: String): List<String> {
        val lines = mutableListOf<String>()
        val middleLine = height / 2
        repeat(height) { i ->
            lines.add(if (i == middleLine) message.take(width).padEnd(width) else " ".repeat(width))
        }
        return lines
    }
}

/**
 * Adapter that renders an event stream as a pane.
 */
class EventStreamPaneAdapter(
    private val terminal: Terminal,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : PaneRenderer {

    private var viewState: WatchViewState? = null
    private var verboseMode: Boolean = false

    fun updateState(viewState: WatchViewState, verboseMode: Boolean = false) {
        this.viewState = viewState
        this.verboseMode = verboseMode
    }

    override fun render(width: Int, height: Int): List<String> {
        val state = viewState ?: return emptyPaneContent(width, height, "No events")

        val lines = mutableListOf<String>()

        // Header
        lines.add(terminal.render(bold(TextColors.cyan("Event Stream"))))
        lines.add("")

        // Events
        val events = if (verboseMode) {
            state.recentSignificantEvents
        } else {
            state.recentSignificantEvents.filter { it.significance != EventSignificance.ROUTINE }
        }

        val maxEvents = height - 4
        if (events.isEmpty()) {
            lines.add(terminal.render(dim("No events yet")))
        } else {
            events.take(maxEvents).forEach { event ->
                lines.add(renderEventLine(event, width))
            }
        }

        // Pad to height
        while (lines.size < height - 1) {
            lines.add("")
        }

        // Footer
        lines.add(terminal.render(dim("v=verbose mode")))

        return lines.map { it.fitToWidth(width) }
    }

    private fun renderEventLine(event: link.socket.ampere.cli.watch.presentation.SignificantEventSummary, width: Int): String {
        val eventColor = when (event.significance) {
            EventSignificance.CRITICAL -> TextColors.red
            EventSignificance.SIGNIFICANT -> TextColors.white
            EventSignificance.ROUTINE -> TextColors.gray
        }

        val icon = when (event.significance) {
            EventSignificance.CRITICAL -> "\u001B[31m!\u001B[0m"
            EventSignificance.SIGNIFICANT -> "\u001B[37m·\u001B[0m"
            EventSignificance.ROUTINE -> "\u001B[90m·\u001B[0m"
        }

        val timeStr = formatTime(event.timestamp)
        val timeSinceStr = formatTimeSince(event.timestamp)
        val maxTextLen = (width - 25).coerceAtLeast(10)
        val text = event.summaryText.take(maxTextLen)

        return "${terminal.render(dim(timeStr))} $icon ${terminal.render(eventColor(text))} ${terminal.render(dim("($timeSinceStr)"))}"
    }

    private fun formatTime(timestamp: Instant): String {
        val localDateTime = timestamp.toLocalDateTime(timeZone)
        return "${localDateTime.hour.toString().padStart(2, '0')}:${localDateTime.minute.toString().padStart(2, '0')}:${localDateTime.second.toString().padStart(2, '0')}"
    }

    private fun formatTimeSince(timestamp: Instant): String {
        val elapsed = clock.now().toEpochMilliseconds() - timestamp.toEpochMilliseconds()
        return when {
            elapsed < 1000 -> "now"
            elapsed < 60_000 -> "${elapsed / 1000}s"
            elapsed < 3600_000 -> "${elapsed / 60_000}m"
            else -> "${elapsed / 3600_000}h"
        }
    }

    private fun emptyPaneContent(width: Int, height: Int, message: String): List<String> {
        val lines = mutableListOf<String>()
        val middleLine = height / 2
        repeat(height) { i ->
            lines.add(if (i == middleLine) message.take(width).padEnd(width) else " ".repeat(width))
        }
        return lines
    }
}
