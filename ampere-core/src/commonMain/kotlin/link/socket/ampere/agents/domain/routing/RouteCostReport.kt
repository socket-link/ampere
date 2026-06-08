package link.socket.ampere.agents.domain.routing

import kotlin.math.roundToLong
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptor
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.cheapestCapable
import link.socket.ampere.domain.ai.provider.ProviderId
import link.socket.ampere.domain.arc.ArcAgentConfig
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ArcRegistry

/**
 * Cheapest capable route chosen for a single step (one agent in an Arc).
 *
 * @property stepLabel The agent role this step runs as.
 * @property chosenProvider Cheapest capable provider for the step's requirement.
 * @property estimatedWattCost Chosen provider's cost-per-Watt (USD per 1000 tokens).
 * @property candidateCount How many capable providers were compared.
 * @property runnerUpProvider Next-cheapest provider, if any.
 * @property savingsVsRunnerUp Per-Watt saving over the runner-up, if any.
 * @property unmet `true` when no registered provider satisfies the requirement.
 */
data class RouteStepCost(
    val stepLabel: String,
    val chosenProvider: ProviderId?,
    val estimatedWattCost: Double,
    val candidateCount: Int,
    val runnerUpProvider: ProviderId? = null,
    val savingsVsRunnerUp: Double? = null,
    val unmet: Boolean = false,
)

/** Cheapest route per step for one Arc, plus the Arc's summed Watt cost. */
data class ArcRouteCost(
    val arcName: String,
    val steps: List<RouteStepCost>,
) {
    /** Sum of every step's estimated cost-per-Watt across the Arc. */
    val totalWattCost: Double
        get() = steps.sumOf { it.estimatedWattCost }
}

/**
 * A dry-run of cost-aware routing across a set of Arcs (AMPR-210): for every
 * step, the cheapest capable provider and its Watt cost, deterministic and
 * side-effect-free. The immediate payoff — it shows where the savings land
 * before a single token is spent.
 */
data class RouteCostReport(
    val arcs: List<ArcRouteCost>,
) {
    /** Total estimated cost-per-Watt across every step of every Arc. */
    val totalWattCost: Double
        get() = arcs.sumOf { it.totalWattCost }

    /** A fixed-width, deterministic text rendering suitable for CLI output. */
    fun render(): String = buildString {
        appendLine("Cost-aware dry run — cheapest capable route per step (USD per Watt)")
        appendLine("=".repeat(68))
        for (arc in arcs) {
            appendLine("${arc.arcName} (total ${arc.totalWattCost.toFixed(DECIMALS)} USD/W)")
            for (step in arc.steps) {
                append("  ")
                append(step.stepLabel.padEnd(STEP_LABEL_WIDTH))
                if (step.unmet) {
                    appendLine("no capable provider")
                } else {
                    append((step.chosenProvider ?: "").padEnd(PROVIDER_WIDTH))
                    append(step.estimatedWattCost.toFixed(DECIMALS).padStart(COST_WIDTH))
                    val savings = step.savingsVsRunnerUp
                    if (step.runnerUpProvider != null && savings != null) {
                        append("  (saves ${savings.toFixed(DECIMALS)} vs ${step.runnerUpProvider})")
                    }
                    appendLine()
                }
            }
        }
        appendLine("-".repeat(68))
        appendLine("All Arcs total: ${totalWattCost.toFixed(DECIMALS)} USD/W")
    }

    private companion object {
        const val STEP_LABEL_WIDTH = 14
        const val PROVIDER_WIDTH = 12
        const val COST_WIDTH = 8
        const val DECIMALS = 4
    }
}

/**
 * Computes a [RouteCostReport] over [arcs] (defaulting to the built-in launch
 * Arcs) by asking the registry for the cheapest capable provider for each
 * step's [CapabilityRequirement].
 *
 * @param registry The descriptor registry cost-aware routing reads.
 * @param requirementFor Maps a step (Arc + agent) to what it needs from a
 *   provider. Defaults to an empty requirement — every provider qualifies, so
 *   the report shows the unconditional cheapest provider per step.
 */
class RouteCostReporter(
    private val registry: ProviderDescriptorRegistry,
    private val requirementFor: (ArcConfig, ArcAgentConfig) -> CapabilityRequirement =
        { _, _ -> CapabilityRequirement() },
) {

    suspend fun report(arcs: List<ArcConfig> = ArcRegistry.list()): RouteCostReport {
        val descriptors = registry.all()
        val arcCosts = arcs.map { arc ->
            ArcRouteCost(
                arcName = arc.name,
                steps = arc.agents.map { agent -> stepCost(arc, agent, descriptors) },
            )
        }
        return RouteCostReport(arcCosts)
    }

    private fun stepCost(
        arc: ArcConfig,
        agent: ArcAgentConfig,
        descriptors: List<ProviderDescriptor>,
    ): RouteStepCost {
        val ranking = descriptors.cheapestCapable(requirementFor(arc, agent))
            ?: return RouteStepCost(
                stepLabel = agent.role,
                chosenProvider = null,
                estimatedWattCost = 0.0,
                candidateCount = 0,
                unmet = true,
            )

        return RouteStepCost(
            stepLabel = agent.role,
            chosenProvider = ranking.chosen.providerId,
            estimatedWattCost = ranking.estimatedWattCost,
            candidateCount = ranking.candidateCount,
            runnerUpProvider = ranking.runnerUp?.providerId,
            savingsVsRunnerUp = ranking.savingsVsRunnerUp,
        )
    }
}

/** Non-negative fixed-decimal rendering without depending on platform formatters. */
private fun Double.toFixed(decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (this * factor).roundToLong()
    val whole = scaled / factor
    val frac = (scaled % factor).toString().padStart(decimals, '0')
    return "$whole.$frac"
}
