package link.socket.ampere.agents.domain.routing.capability

import kotlinx.serialization.Serializable

/**
 * How a provider charges for generation, used by cost-aware routing and by the
 * Watt cost aggregator.
 *
 * Every policy exposes a comparable [usdPerWatt] — the sort key cost-aware
 * routing minimises (AMPR-210). The unit is USD per normalized Watt, where one
 * Watt is 1000 tokens (matching `WattCostAggregator.TOKENS_PER_WATT`), so the
 * key shares the relay's billing unit rather than inventing a new one.
 *
 * Kept deliberately coarse: routing distinguishes a call that costs nothing at
 * all ([Free], e.g. local on-device inference at 0W) from the existing metered
 * token×tier accounting ([Metered]). "Prefer on-device" and "prefer cheapest"
 * are the same rule once local is priced at 0W — local is simply the limiting
 * case of cheapest-capable.
 */
@Serializable
sealed interface CostPolicy {

    /**
     * Representative generation cost in USD per normalized Watt (1 Watt = 1000
     * tokens). The total order cost-aware selection ranks providers by.
     */
    val usdPerWatt: Double

    /** No cost — 0W. Local, on-device generation. Always wins on price. */
    @Serializable
    data object Free : CostPolicy {
        override val usdPerWatt: Double get() = 0.0
    }

    /**
     * The existing token×tier metered behaviour, carrying the provider's
     * representative blended cost-per-Watt so equally-capable providers can be
     * compared. Rates are provider data supplied by the descriptor registry
     * (AMPR-205) — selection never hardcodes them.
     */
    @Serializable
    data class Metered(
        override val usdPerWatt: Double = DEFAULT_USD_PER_WATT,
    ) : CostPolicy

    companion object {
        /**
         * Neutral mid-tier cloud rate used when a descriptor does not specify a
         * cost. Representative only; the registry seeds real per-provider rates.
         */
        const val DEFAULT_USD_PER_WATT: Double = 0.014
    }
}
