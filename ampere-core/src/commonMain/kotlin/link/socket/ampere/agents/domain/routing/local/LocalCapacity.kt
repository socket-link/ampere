package link.socket.ampere.agents.domain.routing.local

import kotlinx.serialization.Serializable
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * A snapshot of what an on-device [LocalInferenceEngine] can serve right now.
 *
 * Returned by [LocalInferenceEngine.probe] so callers can decide whether a
 * device-gated provider is currently usable without attempting a generation.
 * The same snapshot is the input to capability routing's availability gate
 * (AMPR-207): a [link.socket.ampere.agents.domain.routing.RoutingRule.ByCapability]
 * whose descriptor is `availabilityGated` matches only when this reports the
 * target [providerId] [available]. Kept deliberately small and SDK-free.
 *
 * Phase 1 leaves [RoutingContext.localCapacity][link.socket.ampere.agents.domain.routing.RoutingContext.localCapacity]
 * null (no local providers wired); a later phase will populate it from real
 * device probes (e.g. Apple Intelligence readiness, thermal state).
 *
 * @property available Whether the engine can serve a generation right now.
 * @property modelId Identifier of the loaded on-device model, if any.
 * @property maxContextTokens Largest prompt the engine can accept, if known.
 * @property providerId The local provider this snapshot describes, if any. The
 *   availability gate only opens when this matches the candidate descriptor's
 *   provider.
 * @property reason Why the provider is unavailable, surfaced as the
 *   [RoutingEvent.RouteFallback][link.socket.ampere.agents.domain.event.RoutingEvent.RouteFallback]
 *   `failureReason` (e.g. `"apple_intelligence_unavailable"`, `"thermal_throttle"`).
 */
@Serializable
data class LocalCapacity(
    val available: Boolean,
    val modelId: String? = null,
    val maxContextTokens: Int? = null,
    val providerId: ProviderId? = null,
    val reason: String? = null,
) {
    companion object {
        /** Convenience for an engine that cannot currently serve generations. */
        val Unavailable: LocalCapacity = LocalCapacity(available = false)
    }
}
