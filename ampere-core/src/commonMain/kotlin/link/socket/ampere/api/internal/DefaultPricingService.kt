package link.socket.ampere.api.internal

import link.socket.ampere.api.PricingModelKey
import link.socket.ampere.api.PricingOverrides
import link.socket.ampere.api.model.ModelPricing
import link.socket.ampere.api.model.PricingDataVersion
import link.socket.ampere.api.model.PricingEstimateRequest
import link.socket.ampere.api.model.PricingEstimateResult
import link.socket.ampere.api.model.PricingTier
import link.socket.ampere.api.pricingModelKey
import link.socket.ampere.api.service.PricingService
import link.socket.ampere.api.validateModelPricing
import link.socket.ampere.domain.ai.pricing.BundledProviderPricingCatalog
import link.socket.ampere.domain.ai.pricing.ProviderModelPricing
import link.socket.ampere.domain.ai.pricing.ProviderPricingCalculator
import link.socket.ampere.domain.ai.pricing.ProviderPricingCatalog
import link.socket.ampere.domain.ai.pricing.TokenPricingTier

internal class DefaultPricingService(
    private val overrides: PricingOverrides = PricingOverrides(),
    private val bundledCatalogLoader: suspend () -> ProviderPricingCatalog = { BundledProviderPricingCatalog.load() },
) : PricingService {
    private var cachedCatalog: EffectivePricingCatalog? = null

    override suspend fun get(providerId: String, modelId: String): Result<ModelPricing?> = runCatching {
        effectiveCatalog().entriesByKey[pricingModelKey(providerId, modelId)]
    }

    override suspend fun list(): Result<List<ModelPricing>> = runCatching {
        effectiveCatalog().entriesByKey.values.toList()
    }

    override suspend fun version(): Result<PricingDataVersion> = runCatching {
        effectiveCatalog().version
    }

    override suspend fun estimate(request: PricingEstimateRequest): Result<PricingEstimateResult?> = runCatching {
        val catalog = effectiveCatalog()
        val pricing = catalog.entriesByKey[
            pricingModelKey(request.providerId, request.modelId),
        ] ?: return@runCatching null
        val inputTokens = request.usage.inputTokens ?: return@runCatching null
        val outputTokens = request.usage.outputTokens ?: return@runCatching null
        if (inputTokens < 0 || outputTokens < 0) return@runCatching null

        val appliedTier = pricing.tiers.firstOrNull { tier ->
            tier.maxInputTokens == null || inputTokens <= tier.maxInputTokens
        } ?: return@runCatching null

        val estimatedCost = ProviderPricingCalculator.estimateUsd(
            pricing = pricing.toDomainPricing(),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        ) ?: return@runCatching null

        PricingEstimateResult(
            providerId = pricing.providerId,
            modelId = pricing.modelId,
            usage = request.usage.copy(estimatedCost = estimatedCost),
            pricing = pricing,
            appliedTier = appliedTier,
            version = catalog.version,
        )
    }

    private suspend fun effectiveCatalog(): EffectivePricingCatalog {
        cachedCatalog?.let { return it }

        overrides.models.forEach(::validateModelPricing)
        val bundledCatalog = bundledCatalogLoader()
        return bundledCatalog.toEffectiveCatalog(overrides).also { cachedCatalog = it }
    }
}

private data class EffectivePricingCatalog(
    val version: PricingDataVersion,
    val entriesByKey: LinkedHashMap<PricingModelKey, ModelPricing>,
)

private fun ProviderPricingCatalog.toEffectiveCatalog(overrides: PricingOverrides): EffectivePricingCatalog {
    val entriesByKey = linkedMapOf<PricingModelKey, ModelPricing>()

    entries.forEach { pricing ->
        val apiPricing = pricing.toApiPricing()
        entriesByKey[pricingModelKey(apiPricing.providerId, apiPricing.modelId)] = apiPricing
    }
    overrides.models.forEach { pricing ->
        entriesByKey[pricingModelKey(pricing.providerId, pricing.modelId)] = pricing
    }

    return EffectivePricingCatalog(
        version = PricingDataVersion(
            version = version,
            currency = currency,
            publishedAt = publishedAt,
            overridesApplied = overrides.models.size,
        ),
        entriesByKey = LinkedHashMap(entriesByKey),
    )
}

private fun ProviderModelPricing.toApiPricing(): ModelPricing = ModelPricing(
    providerId = providerId,
    modelId = modelId,
    tiers = tiers.map(TokenPricingTier::toApiTier),
)

private fun TokenPricingTier.toApiTier(): PricingTier = PricingTier(
    maxInputTokens = maxInputTokens,
    inputUsdPerMillionTokens = inputUsdPerMillionTokens,
    outputUsdPerMillionTokens = outputUsdPerMillionTokens,
)

private fun ModelPricing.toDomainPricing(): ProviderModelPricing = ProviderModelPricing(
    providerId = providerId,
    modelId = modelId,
    tiers = tiers.map(PricingTier::toDomainTier),
)

private fun PricingTier.toDomainTier(): TokenPricingTier = TokenPricingTier(
    maxInputTokens = maxInputTokens,
    inputUsdPerMillionTokens = inputUsdPerMillionTokens,
    outputUsdPerMillionTokens = outputUsdPerMillionTokens,
)
