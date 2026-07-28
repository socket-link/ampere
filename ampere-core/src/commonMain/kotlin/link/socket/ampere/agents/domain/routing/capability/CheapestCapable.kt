package link.socket.ampere.agents.domain.routing.capability

/**
 * Orders models cheapest-first: by [routingCostPerWatt] ascending, then by
 * [ModelDescriptor.modelName] ascending as a stable, deterministic tie-break.
 * Shared by the relay and the dry-run cost report so both rank candidates
 * identically (AMPR-210).
 */
val CheapestCapableFirst: Comparator<ModelDescriptor> =
    compareBy({ it.routingCostPerWatt }, { it.modelName })

/**
 * The outcome of ranking a set of equally-eligible models by cost: the [chosen]
 * cheapest one, the [runnerUp] it beat (if any), and how many candidates were
 * compared.
 *
 * @property chosen Cheapest capable model; the route the relay resolves to.
 * @property runnerUp Next-cheapest model, or `null` when only one candidate.
 * @property candidateCount Total models compared (always `>= 1`).
 */
data class CostRanking(
    val chosen: ModelDescriptor,
    val runnerUp: ModelDescriptor?,
    val candidateCount: Int,
) {
    /** The chosen model's cost-per-Watt — what this route is estimated to cost. */
    val estimatedWattCost: Double
        get() = chosen.routingCostPerWatt

    /**
     * How much per Watt the chosen model saves over the runner-up, or `null`
     * when there was no runner-up to compare against. Never negative.
     */
    val savingsVsRunnerUp: Double?
        get() = runnerUp?.let { it.routingCostPerWatt - chosen.routingCostPerWatt }
}

/**
 * Ranks the models in this collection that [satisfies] the given [req] by cost
 * (cheapest-capable), or returns `null` when none qualify.
 *
 * Excludes [ModelDescriptor.availabilityGated] models: unlike the live relay
 * (which reads a [link.socket.ampere.agents.domain.routing.local.LocalCapacity]
 * snapshot to confirm a gated model is actually usable before counting it as a
 * candidate — see [link.socket.ampere.agents.domain.routing.RoutingRule.ByCapability]),
 * this dry-run has no live capacity signal, so a gated model (e.g. Rung 0's
 * on-device floor) would otherwise always "win" on price without any
 * confirmation it's really available — reporting a route that live routing
 * might never actually pick.
 *
 * Deterministic: equal cost-per-Watt is broken by `modelName`, so the same
 * inputs always yield the same winner.
 */
fun Iterable<ModelDescriptor>.cheapestCapable(req: CapabilityRequirement): CostRanking? {
    val ranked = filter { it.satisfies(req) && !it.availabilityGated }.sortedWith(CheapestCapableFirst)
    val chosen = ranked.firstOrNull() ?: return null
    return CostRanking(
        chosen = chosen,
        runnerUp = ranked.getOrNull(1),
        candidateCount = ranked.size,
    )
}
