package link.socket.ampere.agents.domain.routing

import link.socket.ampere.agents.domain.routing.capability.CapabilityRung

/**
 * Thrown by [CognitiveRelay.resolve] when the routing requirement declares a
 * [CapabilityRung] floor that no registered model satisfies.
 *
 * Callers that need structured failure metadata should use
 * [CognitiveRelay.resolveWithMetadata] and handle [RoutingResolution.FloorUnmet].
 */
class RoutingFloorUnmetException(
    val requestedFloor: CapabilityRung,
    val bestAvailableRung: CapabilityRung?,
) : Exception(
    "No model meets rung floor ${requestedFloor.ordinal}" +
        (bestAvailableRung?.let { "; best available: ${it.ordinal}" } ?: "; no capable model found"),
)
