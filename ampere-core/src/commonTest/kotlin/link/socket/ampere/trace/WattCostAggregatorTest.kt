package link.socket.ampere.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.domain.ai.UsageTier

class WattCostAggregatorTest {

    @Test
    fun `known token counts produce expected Watt values for usage tier`() {
        val aggregator = WattCostAggregator(UsageTier.TIER_2)

        val cost = aggregator.costFor(
            TokenUsage(
                inputTokens = 1_000,
                outputTokens = 500,
                estimatedCost = 0.006,
            ),
        )

        assertEquals(1_000, cost.inputTokens)
        assertEquals(500, cost.outputTokens)
        assertEquals(0.006, assertNotNull(cost.estimatedUsd), absoluteTolerance = 0.0000001)
        assertEquals(2.25, cost.watts, absoluteTolerance = 0.0000001)
    }

    @Test
    fun `Free cost policy records zero Watts while preserving token accounting`() {
        val aggregator = WattCostAggregator(UsageTier.TIER_2)
        val usage = TokenUsage(
            inputTokens = 1_000,
            outputTokens = 500,
            estimatedCost = 0.006,
        )

        val cost = aggregator.costFor(usage, CostPolicy.Free)

        assertEquals(0.0, cost.watts, absoluteTolerance = 0.0000001)
        assertEquals(1_000, cost.inputTokens)
        assertEquals(500, cost.outputTokens)
        assertEquals(0.006, assertNotNull(cost.estimatedUsd), absoluteTolerance = 0.0000001)
    }

    @Test
    fun `Metered cost policy matches the default token times tier computation`() {
        val aggregator = WattCostAggregator(UsageTier.TIER_2)
        val usage = TokenUsage(
            inputTokens = 1_000,
            outputTokens = 500,
            estimatedCost = 0.006,
        )

        val metered = aggregator.costFor(usage, CostPolicy.Metered)

        assertEquals(aggregator.costFor(usage), metered)
        assertEquals(2.25, metered.watts, absoluteTolerance = 0.0000001)
    }
}
