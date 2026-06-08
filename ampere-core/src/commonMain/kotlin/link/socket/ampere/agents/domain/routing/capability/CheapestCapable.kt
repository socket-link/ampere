package link.socket.ampere.agents.domain.routing.capability

/**
 * Orders providers cheapest-first: by [routingCostPerWatt] ascending, then by
 * [ProviderDescriptor.providerId] ascending as a stable, deterministic
 * tie-break. Shared by the relay and the dry-run cost report so both rank
 * candidates identically (AMPR-210).
 */
val CheapestCapableFirst: Comparator<ProviderDescriptor> =
    compareBy({ it.routingCostPerWatt }, { it.providerId })

/**
 * The outcome of ranking a set of equally-eligible providers by cost: the
 * [chosen] cheapest one, the [runnerUp] it beat (if any), and how many
 * candidates were compared.
 *
 * @property chosen Cheapest capable provider; the route the relay resolves to.
 * @property runnerUp Next-cheapest provider, or `null` when only one candidate.
 * @property candidateCount Total providers compared (always `>= 1`).
 */
data class CostRanking(
    val chosen: ProviderDescriptor,
    val runnerUp: ProviderDescriptor?,
    val candidateCount: Int,
) {
    /** The chosen provider's cost-per-Watt — what this route is estimated to cost. */
    val estimatedWattCost: Double
        get() = chosen.routingCostPerWatt

    /**
     * How much per Watt the chosen provider saves over the runner-up, or `null`
     * when there was no runner-up to compare against. Never negative.
     */
    val savingsVsRunnerUp: Double?
        get() = runnerUp?.let { it.routingCostPerWatt - chosen.routingCostPerWatt }
}

/**
 * Ranks the providers in this collection that [satisfies] the given [req] by
 * cost (cheapest-capable), or returns `null` when none qualify.
 *
 * Deterministic: equal cost-per-Watt is broken by `providerId`, so the same
 * inputs always yield the same winner.
 */
fun Iterable<ProviderDescriptor>.cheapestCapable(req: CapabilityRequirement): CostRanking? {
    val ranked = filter { it.satisfies(req) }.sortedWith(CheapestCapableFirst)
    val chosen = ranked.firstOrNull() ?: return null
    return CostRanking(
        chosen = chosen,
        runnerUp = ranked.getOrNull(1),
        candidateCount = ranked.size,
    )
}
