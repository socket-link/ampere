package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase

/**
 * Immutable snapshot of watch state for rendering.
 */
data class WatchViewState(
    val systemVitals: SystemVitals,
    val agentStates: Map<String, AgentActivityState>,
    val recentSignificantEvents: List<SignificantEventSummary>,
    val recentProviderTelemetry: List<ProviderCallTelemetrySummary> = emptyList()
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

/**
 * Telemetry summary for bridging provider calls into Phosphor emitter metadata.
 */
data class ProviderCallTelemetrySummary(
    val eventId: String,
    val agentId: String,
    val cognitivePhase: CognitivePhase?,
    val latencyMs: Long,
    val estimatedCost: Double?,
    val totalTokens: Int?,
    val success: Boolean
)
