package link.socket.ampere.api.model

import kotlinx.serialization.Serializable

/**
 * Effective token pricing for a provider/model pair.
 */
@link.socket.ampere.api.AmpereStableApi
@Serializable
data class ModelPricing(
    val providerId: String,
    val modelId: String,
    val tiers: List<PricingTier>,
)

/**
 * Token price tier expressed in USD per million tokens.
 */
@link.socket.ampere.api.AmpereStableApi
@Serializable
data class PricingTier(
    val maxInputTokens: Int? = null,
    val inputUsdPerMillionTokens: Double,
    val outputUsdPerMillionTokens: Double,
)
