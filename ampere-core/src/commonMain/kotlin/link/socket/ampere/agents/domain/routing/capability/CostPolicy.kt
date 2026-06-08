package link.socket.ampere.agents.domain.routing.capability

import kotlinx.serialization.Serializable

/**
 * How a provider charges for generation, used by cost-aware routing and by the
 * Watt cost aggregator (consumers land in T5/T7).
 *
 * Kept deliberately coarse: the only distinction routing needs today is whether
 * a call costs anything at all ([Free], e.g. local on-device inference) or
 * follows the existing metered token×tier accounting ([Metered]).
 */
@Serializable
sealed interface CostPolicy {
    /** No cost — 0W. Local, on-device generation. */
    @Serializable
    data object Free : CostPolicy

    /** The existing token×tier metered behaviour. */
    @Serializable
    data object Metered : CostPolicy
}
