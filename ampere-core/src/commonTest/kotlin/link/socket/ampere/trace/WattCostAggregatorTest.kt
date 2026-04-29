package link.socket.ampere.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
}
