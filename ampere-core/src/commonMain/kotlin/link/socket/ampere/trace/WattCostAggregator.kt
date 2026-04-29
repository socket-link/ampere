package link.socket.ampere.trace

import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.domain.ai.UsageTier

/**
 * Converts telemetry token accounting into Ampere's normalized Watt units.
 *
 * The aggregator consumes the existing TokenUsage emitted by provider telemetry;
 * it does not estimate tokens independently.
 */
class WattCostAggregator(
    private val usageTier: UsageTier = UsageTier.TIER_1,
) {
    fun costFor(usage: TokenUsage): WattCost {
        val inputTokens = usage.inputTokens ?: 0
        val outputTokens = usage.outputTokens ?: 0
        val totalTokens = inputTokens + outputTokens

        return WattCost(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedUsd = usage.estimatedCost,
            watts = totalTokens.toDouble() / TOKENS_PER_WATT * multiplierFor(usageTier),
        )
    }

    fun costFor(invocation: ModelInvocationTrace): WattCost =
        costFor(
            TokenUsage(
                inputTokens = invocation.inputTokens,
                outputTokens = invocation.outputTokens,
                estimatedCost = invocation.estimatedUsd,
            ),
        )

    fun aggregate(invocations: List<ModelInvocationTrace>): WattCost =
        invocations.fold(WattCost()) { total, invocation ->
            total.plus(invocation.wattCost.takeUnless { it == WattCost() } ?: costFor(invocation))
        }

    companion object {
        const val TOKENS_PER_WATT: Double = 1_000.0

        fun multiplierFor(usageTier: UsageTier): Double = when (usageTier) {
            UsageTier.FREE -> 0.5
            UsageTier.TIER_1 -> 1.0
            UsageTier.TIER_2 -> 1.5
            UsageTier.TIER_3 -> 2.0
            UsageTier.TIER_4 -> 2.5
            UsageTier.TIER_5 -> 3.0
        }
    }
}
