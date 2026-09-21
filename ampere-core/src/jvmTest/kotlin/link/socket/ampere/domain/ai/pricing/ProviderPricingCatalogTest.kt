package link.socket.ampere.domain.ai.pricing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class ProviderPricingCatalogTest {

    @Test
    fun `bundled pricing resource parses successfully`() = runTest {
        val catalog = BundledProviderPricingCatalog.load()

        assertEquals(1, catalog.version)
        assertEquals("USD", catalog.currency)
        assertEquals("2026-09-20", catalog.publishedAt)
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

    @Test
    fun `every bundled cloud model has a pricing entry`() = runTest {
        val bundled = AIModel_Claude.ALL_MODELS.map { AIProvider_Anthropic.id to it.name } +
            AIModel_OpenAI.ALL_MODELS.map { AIProvider_OpenAI.id to it.name } +
            AIModel_Gemini.ALL_MODELS.map { AIProvider_Google.id to it.name }

        val unpriced = bundled.filter { (providerId, modelId) ->
            BundledProviderPricingCatalog.find(providerId = providerId, modelId = modelId) == null
        }

        assertTrue(unpriced.isEmpty(), "bundled models with no pricing entry: $unpriced")
    }

    @Test
    fun `current generation models carry their published rates`() = runTest {
        val expected = mapOf(
            ("anthropic" to "claude-opus-5") to (5.0 to 25.0),
            ("anthropic" to "claude-sonnet-5") to (2.0 to 10.0),
            ("anthropic" to "claude-fable-5-1") to (10.0 to 50.0),
            ("openai" to "gpt-5.4") to (2.5 to 15.0),
            ("openai" to "gpt-5.5") to (5.0 to 30.0),
            ("openai" to "gpt-5.4-mini") to (0.75 to 4.5),
            ("google" to "gemini-3.1-pro-preview") to (2.0 to 12.0),
            ("google" to "gemini-3.8-flash") to (0.75 to 3.75),
        )
        expected.forEach { (key, rates) ->
            val pricing =
                assertNotNull(BundledProviderPricingCatalog.find(providerId = key.first, modelId = key.second))
            val firstTier = pricing.tiers.first()
            assertEquals(rates.first, firstTier.inputUsdPerMillionTokens, "${key.second} input")
            assertEquals(rates.second, firstTier.outputUsdPerMillionTokens, "${key.second} output")
        }
    }
}
