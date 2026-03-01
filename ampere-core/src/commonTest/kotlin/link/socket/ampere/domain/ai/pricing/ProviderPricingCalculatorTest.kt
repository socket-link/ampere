package link.socket.ampere.domain.ai.pricing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProviderPricingCalculatorTest {

    private val pricing = ProviderModelPricing(
        providerId = "google",
        modelId = "gemini-2.5-pro",
        tiers = listOf(
            TokenPricingTier(
                maxInputTokens = 200_000,
                inputUsdPerMillionTokens = 1.25,
                outputUsdPerMillionTokens = 10.0,
            ),
            TokenPricingTier(
                inputUsdPerMillionTokens = 2.5,
                outputUsdPerMillionTokens = 15.0,
            ),
        ),
    )

    @Test
    fun `calculates cost for known model token counts`() {
        val estimatedCost = ProviderPricingCalculator.estimateUsd(
            pricing = pricing,
            inputTokens = 1_000,
            outputTokens = 500,
        )

        assertEquals(0.00625, assertNotNull(estimatedCost), absoluteTolerance = 0.0000001)
    }

    @Test
    fun `uses higher tier pricing when input crosses threshold`() {
        val estimatedCost = ProviderPricingCalculator.estimateUsd(
            pricing = pricing,
            inputTokens = 250_000,
            outputTokens = 10_000,
        )

        assertEquals(0.775, assertNotNull(estimatedCost), absoluteTolerance = 0.0000001)
    }

    @Test
    fun `unknown model returns no estimate`() {
        val estimatedCost = ProviderPricingCalculator.estimateUsd(
            pricing = null,
            inputTokens = 1_000,
            outputTokens = 500,
        )

        assertNull(estimatedCost)
    }
}
