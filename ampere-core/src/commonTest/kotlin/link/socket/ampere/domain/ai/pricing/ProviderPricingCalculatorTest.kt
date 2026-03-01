package link.socket.ampere.domain.ai.pricing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ProviderPricingCalculatorTest {

    @Test
    fun `calculates cost for known model token counts`() = runTest {
        val estimatedCost = ProviderPricingCalculator.estimateUsd(
            providerId = "openai",
            modelId = "gpt-4.1",
            inputTokens = 1_000,
            outputTokens = 500,
        )

        assertEquals(0.006, assertNotNull(estimatedCost), absoluteTolerance = 0.0000001)
    }

    @Test
    fun `uses higher tier pricing when input crosses threshold`() = runTest {
        val estimatedCost = ProviderPricingCalculator.estimateUsd(
            providerId = "google",
            modelId = "gemini-2.5-pro",
            inputTokens = 250_000,
            outputTokens = 10_000,
        )

        assertEquals(0.775, assertNotNull(estimatedCost), absoluteTolerance = 0.0000001)
    }

    @Test
    fun `unknown model returns no estimate`() = runTest {
        val estimatedCost = ProviderPricingCalculator.estimateUsd(
            providerId = "openai",
            modelId = "unknown-model",
            inputTokens = 1_000,
            outputTokens = 500,
        )

        assertNull(estimatedCost)
    }
}
