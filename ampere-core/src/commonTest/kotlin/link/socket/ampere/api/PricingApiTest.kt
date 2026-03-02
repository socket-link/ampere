package link.socket.ampere.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import link.socket.ampere.api.internal.DefaultPricingService
import link.socket.ampere.api.model.ModelPricing
import link.socket.ampere.api.model.PricingDataVersion
import link.socket.ampere.api.model.PricingEstimateResult
import link.socket.ampere.api.model.PricingTier
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.pricing.ProviderModelPricing
import link.socket.ampere.domain.ai.pricing.ProviderPricingCatalog
import link.socket.ampere.domain.ai.pricing.TokenPricingTier
import link.socket.ampere.dsl.config.AnthropicConfig

class PricingApiTest {
    @Test
    fun `pricing models round trip through serialization`() {
        val result = PricingEstimateResult(
            providerId = "openai",
            modelId = "gpt-4.1",
            usage = TokenUsage(
                inputTokens = 1_000,
                outputTokens = 500,
                estimatedCost = 0.006,
            ),
            pricing = ModelPricing(
                providerId = "openai",
                modelId = "gpt-4.1",
                tiers = listOf(
                    PricingTier(
                        inputUsdPerMillionTokens = 2.0,
                        outputUsdPerMillionTokens = 8.0,
                    ),
                ),
            ),
            appliedTier = PricingTier(
                inputUsdPerMillionTokens = 2.0,
                outputUsdPerMillionTokens = 8.0,
            ),
            version = PricingDataVersion(
                version = 1,
                currency = "USD",
                publishedAt = "2026-03-02",
                overridesApplied = 1,
            ),
        )

        val json = DEFAULT_JSON.encodeToString(result)
        val decoded = DEFAULT_JSON.decodeFromString(PricingEstimateResult.serializer(), json)

        assertEquals(result, decoded)
    }

    @Test
    fun `AmpereConfig pricing DSL accumulates overrides by provider and model`() {
        val config = AmpereConfig.Builder().apply {
            provider(AnthropicConfig())
            pricing {
                model("openai", "gpt-4.1") {
                    tier(
                        inputUsdPerMillionTokens = 2.0,
                        outputUsdPerMillionTokens = 8.0,
                    )
                }
            }
            pricing {
                model("OPENAI", "GPT-4.1") {
                    tier(
                        inputUsdPerMillionTokens = 1.0,
                        outputUsdPerMillionTokens = 2.0,
                    )
                }
                model("self-hosted", "mixtral-enterprise") {
                    tier(
                        inputUsdPerMillionTokens = 0.0,
                        outputUsdPerMillionTokens = 0.0,
                    )
                }
            }
        }.build()

        assertEquals(2, config.pricingOverrides.models.size)
        val overridden = config.pricingOverrides.models.first { it.providerId.equals("OPENAI", ignoreCase = true) }
        assertEquals(1.0, overridden.tiers.single().inputUsdPerMillionTokens)
        assertTrue(
            config.pricingOverrides.models.any { it.providerId == "self-hosted" && it.modelId == "mixtral-enterprise" },
        )
    }

    @Test
    fun `default pricing service merges overrides and custom models`() = runTest {
        val service = DefaultPricingService(
            overrides = PricingOverrides(
                models = listOf(
                    ModelPricing(
                        providerId = "openai",
                        modelId = "gpt-4.1",
                        tiers = listOf(
                            PricingTier(
                                inputUsdPerMillionTokens = 1.0,
                                outputUsdPerMillionTokens = 2.0,
                            ),
                        ),
                    ),
                    ModelPricing(
                        providerId = "self-hosted",
                        modelId = "mixtral-enterprise",
                        tiers = listOf(
                            PricingTier(
                                inputUsdPerMillionTokens = 0.0,
                                outputUsdPerMillionTokens = 0.0,
                            ),
                        ),
                    ),
                ),
            ),
            bundledCatalogLoader = {
                ProviderPricingCatalog(
                    version = 7,
                    currency = "USD",
                    publishedAt = "2026-02-28",
                    entries = listOf(
                        ProviderModelPricing(
                            providerId = "openai",
                            modelId = "gpt-4.1",
                            tiers = listOf(
                                TokenPricingTier(
                                    inputUsdPerMillionTokens = 2.0,
                                    outputUsdPerMillionTokens = 8.0,
                                ),
                            ),
                        ),
                    ),
                )
            },
        )

        val knownPricing = service.get("OPENAI", "GPT-4.1").getOrThrow()
        val allPricing = service.list().getOrThrow()
        val version = service.version().getOrThrow()

        assertNotNull(knownPricing)
        assertEquals(1.0, knownPricing.tiers.single().inputUsdPerMillionTokens)
        assertEquals(2, allPricing.size)
        assertTrue(allPricing.any { it.providerId == "self-hosted" && it.modelId == "mixtral-enterprise" })
        assertEquals(7, version.version)
        assertEquals("2026-02-28", version.publishedAt)
        assertEquals(2, version.overridesApplied)
    }

    @Test
    fun `default pricing service estimates cost from effective pricing`() = runTest {
        val service = DefaultPricingService(
            overrides = PricingOverrides(
                models = listOf(
                    ModelPricing(
                        providerId = "openai",
                        modelId = "gpt-4.1",
                        tiers = listOf(
                            PricingTier(
                                inputUsdPerMillionTokens = 1.0,
                                outputUsdPerMillionTokens = 2.0,
                            ),
                        ),
                    ),
                ),
            ),
            bundledCatalogLoader = {
                ProviderPricingCatalog(
                    version = 3,
                    currency = "USD",
                    publishedAt = "2026-03-01",
                    entries = emptyList(),
                )
            },
        )

        val estimate = service.estimate(
            providerId = "openai",
            modelId = "gpt-4.1",
            usage = TokenUsage(
                inputTokens = 1_000,
                outputTokens = 500,
            ),
        ).getOrThrow()

        assertNotNull(estimate)
        assertEquals(0.002, assertNotNull(estimate.usage.estimatedCost), absoluteTolerance = 0.0000001)
        assertEquals(1.0, estimate.appliedTier.inputUsdPerMillionTokens)
        assertEquals(3, estimate.version.version)
    }
}
