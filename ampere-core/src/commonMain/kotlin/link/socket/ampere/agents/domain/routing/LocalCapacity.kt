package link.socket.ampere.agents.domain.routing

import kotlinx.serialization.Serializable
import link.socket.ampere.domain.ai.provider.ProviderId

/**
 * A runtime snapshot of whether a device-gated local provider can serve this
 * call right now.
 *
 * Availability is a property of the *context*, not the rule: the relay's
 * [RoutingRule.matches][RoutingRule] predicates stay pure and only read what the
 * context tells them. A [RoutingRule.ByCapability] whose descriptor is
 * `availabilityGated` consults this snapshot before claiming a match — see
 * [link.socket.ampere.agents.domain.routing.capability.ProviderDescriptor.availabilityGated].
 *
 * Phase 1 leaves this null (no local providers wired); a later phase will
 * populate it from real device probes (e.g. Apple Intelligence readiness,
 * thermal state).
 *
 * @property available Whether the local provider can serve a call right now.
 * @property providerId The local provider this snapshot describes, if any. A
 *   gate only opens when this matches the candidate descriptor's provider.
 * @property reason Why the provider is unavailable, surfaced as the
 *   [RoutingEvent.RouteFallback][link.socket.ampere.agents.domain.event.RoutingEvent.RouteFallback]
 *   `failureReason` (e.g. `"apple_intelligence_unavailable"`, `"thermal_throttle"`).
 */
@Serializable
data class LocalCapacity(
    val available: Boolean,
    val providerId: ProviderId? = null,
    val reason: String? = null,
)
