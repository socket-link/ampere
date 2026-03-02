package link.socket.ampere.api.service

import link.socket.ampere.api.model.ModelPricing
import link.socket.ampere.api.model.PricingDataVersion
import link.socket.ampere.api.model.PricingEstimateRequest
import link.socket.ampere.api.model.PricingEstimateResult
import link.socket.ampere.api.model.TokenUsage

/**
 * SDK service for bundled model pricing lookup and token cost estimation.
 *
 * ```
 * val pricing = ampere.pricing.get("openai", "gpt-4.1").getOrNull()
 * val estimate = ampere.pricing.estimate(
 *     "openai",
 *     "gpt-4.1",
 *     TokenUsage(inputTokens = 1_000, outputTokens = 500),
 * ).getOrNull()
 * ```
 */
@link.socket.ampere.api.AmpereStableApi
interface PricingService {

    /**
     * Get pricing for a provider/model pair, or null if none is known.
     */
    suspend fun get(providerId: String, modelId: String): Result<ModelPricing?>

    /**
     * List all effective pricing entries, including consumer overrides.
     */
    suspend fun list(): Result<List<ModelPricing>>

    /**
     * Get version metadata for the effective pricing data set.
     */
    suspend fun version(): Result<PricingDataVersion>

    /**
     * Estimate token cost using the effective pricing data set.
     *
     * Returns null when the model is unknown or token counts are unavailable.
     */
    suspend fun estimate(request: PricingEstimateRequest): Result<PricingEstimateResult?>

    /**
     * Convenience overload for [estimate].
     */
    suspend fun estimate(
        providerId: String,
        modelId: String,
        usage: TokenUsage,
    ): Result<PricingEstimateResult?> = estimate(
        PricingEstimateRequest(
            providerId = providerId,
            modelId = modelId,
            usage = usage,
        ),
    )
}
