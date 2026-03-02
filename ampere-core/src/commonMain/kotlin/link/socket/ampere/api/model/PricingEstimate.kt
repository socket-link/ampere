package link.socket.ampere.api.model

import kotlinx.serialization.Serializable

/**
 * Inputs for pricing estimation.
 */
@link.socket.ampere.api.AmpereStableApi
@Serializable
data class PricingEstimateRequest(
    val providerId: String,
    val modelId: String,
    val usage: TokenUsage,
)

/**
 * Estimated cost plus the pricing data used to compute it.
 */
@link.socket.ampere.api.AmpereStableApi
@Serializable
data class PricingEstimateResult(
    val providerId: String,
    val modelId: String,
    val usage: TokenUsage,
    val pricing: ModelPricing,
    val appliedTier: PricingTier,
    val version: PricingDataVersion,
)
