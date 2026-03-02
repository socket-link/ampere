package link.socket.ampere.domain.ai.pricing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ProviderPricingCatalogTest {

    @Test
    fun `bundled pricing resource parses successfully`() = runTest {
        val catalog = BundledProviderPricingCatalog.load()

        assertEquals(1, catalog.version)
        assertEquals("USD", catalog.currency)
        assertEquals("2026-03-02", catalog.publishedAt)
        assertTrue(catalog.entries.isNotEmpty())
    }

    @Test
    fun `known provider and model pairs resolve to non zero pricing`() = runTest {
        val openAiPricing = BundledProviderPricingCatalog.find(
            providerId = "openai",
            modelId = "gpt-4.1",
        )
        val anthropicPricing = BundledProviderPricingCatalog.find(
            providerId = "anthropic",
            modelId = "claude-sonnet-4-0",
        )
        val googlePricing = BundledProviderPricingCatalog.find(
            providerId = "google",
            modelId = "gemini-2.5-flash",
        )

        assertNotNull(openAiPricing)
        assertNotNull(anthropicPricing)
        assertNotNull(googlePricing)
        assertTrue(openAiPricing.tiers.any { it.inputUsdPerMillionTokens > 0.0 && it.outputUsdPerMillionTokens > 0.0 })
        assertTrue(
            anthropicPricing.tiers.any { it.inputUsdPerMillionTokens > 0.0 && it.outputUsdPerMillionTokens > 0.0 },
        )
        assertTrue(googlePricing.tiers.any { it.inputUsdPerMillionTokens > 0.0 && it.outputUsdPerMillionTokens > 0.0 })
    }
}
