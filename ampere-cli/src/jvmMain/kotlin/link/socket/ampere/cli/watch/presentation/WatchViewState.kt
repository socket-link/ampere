package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Instant

/**
 * Immutable snapshot of watch state for rendering.
 */
data class WatchViewState(
    val systemVitals: SystemVitals,
    val agentStates: Map<String, AgentActivityState>,
    val recentSignificantEvents: List<SignificantEventSummary>
)

/**
 * Condensed view of an event for the dashboard feed.
 */
data class SignificantEventSummary(
    val eventId: String,
    val timestamp: Instant,
    val eventType: String,
    val sourceAgentName: String,
    val summaryText: String,
    val significance: EventSignificance
)
