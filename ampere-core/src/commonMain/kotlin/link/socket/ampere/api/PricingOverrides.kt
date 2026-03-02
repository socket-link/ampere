package link.socket.ampere.api

import kotlinx.serialization.Serializable
import link.socket.ampere.api.model.ModelPricing
import link.socket.ampere.api.model.PricingTier

/**
 * Consumer-provided pricing entries that override bundled model rates.
 */
@AmpereStableApi
@Serializable
data class PricingOverrides(
    val models: List<ModelPricing> = emptyList(),
)

/**
 * Builder for [PricingOverrides].
 */
@AmpereStableApi
class PricingOverridesBuilder {
    private val modelsByKey = linkedMapOf<PricingModelKey, ModelPricing>()

    /**
     * Add or replace pricing for a provider/model pair.
     */
    fun model(pricing: ModelPricing) {
        validateModelPricing(pricing)
        modelsByKey[pricingModelKey(pricing.providerId, pricing.modelId)] = pricing
    }

    /**
     * Add or replace pricing for a provider/model pair using the DSL.
     */
    fun model(
        providerId: String,
        modelId: String,
        configure: ModelPricingBuilder.() -> Unit,
    ) {
        model(ModelPricingBuilder(providerId = providerId, modelId = modelId).apply(configure).build())
    }

    internal fun build(): PricingOverrides = PricingOverrides(models = modelsByKey.values.toList())
}

/**
 * Builder for a single [ModelPricing] entry.
 */
@AmpereStableApi
class ModelPricingBuilder internal constructor(
    private val providerId: String,
    private val modelId: String,
) {
    private val tiers = mutableListOf<PricingTier>()

    fun tier(
        maxInputTokens: Int? = null,
        inputUsdPerMillionTokens: Double,
        outputUsdPerMillionTokens: Double,
    ) {
        val tier = PricingTier(
            maxInputTokens = maxInputTokens,
            inputUsdPerMillionTokens = inputUsdPerMillionTokens,
            outputUsdPerMillionTokens = outputUsdPerMillionTokens,
        )
        validatePricingTier(tier)
        tiers += tier
    }

    internal fun build(): ModelPricing {
        val pricing = ModelPricing(
            providerId = providerId,
            modelId = modelId,
            tiers = tiers.toList(),
        )
        validateModelPricing(pricing)
        return pricing
    }
}

internal data class PricingModelKey(
    val providerId: String,
    val modelId: String,
)

internal fun pricingModelKey(providerId: String, modelId: String): PricingModelKey = PricingModelKey(
    providerId = providerId.trim().lowercase(),
    modelId = modelId.trim().lowercase(),
)

internal fun validateModelPricing(pricing: ModelPricing) {
    require(pricing.providerId.isNotBlank()) { "Pricing providerId cannot be blank." }
    require(pricing.modelId.isNotBlank()) { "Pricing modelId cannot be blank." }
    require(pricing.tiers.isNotEmpty()) {
        "Pricing entry ${pricing.providerId}/${pricing.modelId} must include at least one tier."
    }
    pricing.tiers.forEach(::validatePricingTier)
}

internal fun validatePricingTier(tier: PricingTier) {
    require(tier.maxInputTokens == null || tier.maxInputTokens > 0) {
        "Pricing tier maxInputTokens must be positive when provided."
    }
    require(tier.inputUsdPerMillionTokens >= 0.0) {
        "Pricing tier inputUsdPerMillionTokens cannot be negative."
    }
    require(tier.outputUsdPerMillionTokens >= 0.0) {
        "Pricing tier outputUsdPerMillionTokens cannot be negative."
    }
}
