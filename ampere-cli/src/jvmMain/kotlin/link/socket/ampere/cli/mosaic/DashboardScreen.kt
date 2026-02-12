package link.socket.ampere.cli.mosaic

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.cli.watch.presentation.AgentActivityState
import link.socket.ampere.cli.watch.presentation.AgentState
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.SignificantEventSummary
import link.socket.ampere.cli.watch.presentation.SystemState
import link.socket.ampere.cli.watch.presentation.SystemVitals
import link.socket.ampere.cli.watch.presentation.WatchViewState
import link.socket.ampere.renderer.SparkColors

/**
 * Mosaic composable that renders the AMPERE dashboard.
 * Replaces the imperative DashboardRenderer with declarative Compose UI.
 */
@Composable
fun DashboardScreen(
    viewState: WatchViewState,
    frameTick: Long,
    clock: Clock = Clock.System,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    Column {
        SystemVitalsHeader(viewState.systemVitals, clock)
        Text("")
        AgentActivityPanel(viewState.agentStates, frameTick)
        Text("")
        RecentEventsPanel(
            viewState.recentSignificantEvents,
            viewState.agentStates.values.associate { it.displayName to it.affinityName },
        )
        Text("")
        DashboardFooter()
    }
}

private val spinnerFrames = listOf("◐", "◓", "◑", "◒")

@Composable
private fun SystemVitalsHeader(vitals: SystemVitals, clock: Clock) {
    val stateColor = when (vitals.systemState) {
        SystemState.IDLE -> AmpereColors.green
        SystemState.WORKING -> AmpereColors.blue
        SystemState.ATTENTION_NEEDED -> AmpereColors.red
    }

    val lastEventText = vitals.lastSignificantEventTime?.let { timestamp ->
        val elapsed = clock.now().toEpochMilliseconds() - timestamp.toEpochMilliseconds()
        formatDuration(elapsed)
    } ?: "never"

    Text(buildAnnotatedString {
        withStyle(AmpereStyles.boldColored(AmpereColors.cyan)) {
            append("AMPERE Dashboard")
        }
        append(" • ${vitals.activeAgentCount} agents active • ")
        withStyle(SpanStyle(color = stateColor)) {
            append(vitals.systemState.name.lowercase())
        }
        append(" • last event: $lastEventText")
    })
}

@Composable
private fun AgentActivityPanel(
    states: Map<String, AgentActivityState>,
    frameTick: Long,
) {
    val stateWidth = AgentState.values().maxOf { it.displayText.length }

    Text(buildAnnotatedString {
        withStyle(AmpereStyles.bold()) { append("Agent Activity") }
    })

    if (states.isEmpty()) {
        Text(buildAnnotatedString {
            withStyle(AmpereStyles.dim()) { append("No agents running") }
        })
        return
    }

    val nameWidth = 20
    val spinnerCount = spinnerFrames.size

    states.values.sortedBy { it.displayName }.forEachIndexed { index, state ->
        val spinnerIndex = ((frameTick + index) % spinnerCount).toInt()
        val spinnerSymbol = spinnerFrames[spinnerIndex]
        val stateColor = when (state.currentState) {
            AgentState.WORKING -> AmpereColors.green
            AgentState.THINKING -> AmpereColors.yellow
            AgentState.IDLE -> Color(128, 128, 128)
            AgentState.IN_MEETING -> AmpereColors.blue
            AgentState.WAITING -> AmpereColors.yellow
        }
        val name = state.displayName.take(nameWidth).padEnd(nameWidth)
        val nameColor = state.affinityName?.let { AmpereColors.forAffinityName(it) }
            ?: AmpereColors.white
        val stateText = state.currentState.displayText.uppercase().padEnd(stateWidth)
        val depthIndicator = SparkColors.renderDepthIndicator(
            state.sparkDepth.coerceAtLeast(0),
            SparkColors.DepthDisplayStyle.DOTS,
        )

        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = nameColor)) { append(spinnerSymbol) }
            append(" ")
            withStyle(SpanStyle(color = nameColor)) { append(name) }
            append(" ")
            withStyle(SpanStyle(color = stateColor)) { append(stateText) }
            append("  ")
            withStyle(AmpereStyles.dim()) { append(depthIndicator) }
        })
    }
}

@Composable
private fun RecentEventsPanel(
    events: List<SignificantEventSummary>,
    affinityByAgentName: Map<String, String?>,
    clock: Clock = Clock.System,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    Text(buildAnnotatedString {
        withStyle(AmpereStyles.bold()) { append("Recent Events") }
    })

    if (events.isEmpty()) {
        Text(buildAnnotatedString {
            withStyle(AmpereStyles.dim()) { append("No recent activity") }
        })
        return
    }

    events.take(10).forEach { event ->
        val eventColor = when (event.significance) {
            EventSignificance.CRITICAL -> AmpereColors.red
            EventSignificance.SIGNIFICANT -> AmpereColors.white
            EventSignificance.ROUTINE -> Color(128, 128, 128)
        }

        val timeStr = formatTime(event.timestamp, timeZone)
        val affinityName = affinityByAgentName[event.sourceAgentName]
        val agentColor = affinityName?.let { AmpereColors.forAffinityName(it) }
            ?: Color(128, 128, 128)

        Text(buildAnnotatedString {
            withStyle(AmpereStyles.dim()) { append(timeStr) }
            append(" ")
            withStyle(SpanStyle(color = eventColor)) { append(event.summaryText) }
            append(" ")
            withStyle(AmpereStyles.dim()) { append("from ") }
            withStyle(SpanStyle(color = agentColor)) { append(event.sourceAgentName) }
        })
    }
}

@Composable
private fun DashboardFooter() {
    Text(buildAnnotatedString {
        withStyle(AmpereStyles.dim()) {
            append("Ctrl+C to stop • Updates every second")
        }
    })
}

private fun formatDuration(milliseconds: Long): String = when {
    milliseconds < 1000 -> "just now"
    milliseconds < 60_000 -> "${milliseconds / 1000}s ago"
    milliseconds < 3600_000 -> "${milliseconds / 60_000}m ago"
    milliseconds < 86400_000 -> "${milliseconds / 3600_000}h ago"
    else -> "${milliseconds / 86400_000}d ago"
}

private fun formatTime(timestamp: Instant, timeZone: TimeZone): String {
    val localDateTime = timestamp.toLocalDateTime(timeZone)
    return buildString {
        append(localDateTime.hour.toString().padStart(2, '0'))
        append(":")
        append(localDateTime.minute.toString().padStart(2, '0'))
        append(":")
        append(localDateTime.second.toString().padStart(2, '0'))
    }
}
