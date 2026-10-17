package link.socket.ampere.cli.watch.presentation

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.ui.inference.OnDeviceIndicatorModel

/**
 * Immutable snapshot of watch state for rendering.
 */
data class WatchViewState(
    val systemVitals: SystemVitals,
    val agentStates: Map<String, AgentActivityState>,
    val recentSignificantEvents: List<SignificantEventSummary>,
    val recentProviderTelemetry: List<ProviderCallTelemetrySummary> = emptyList(),
    /**
     * The on-device indicator (AMPR-375), or null when no on-device engine is bound. Null keeps
     * the status bar exactly as it was before the engine existed.
     */
    val onDevice: OnDeviceIndicatorModel? = null,
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
    val significance: EventSignificance,
    /** A model call that ran on this device, as the on-device session's own client decides it (AMPR-375). */
    val onDevice: Boolean = false,
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
