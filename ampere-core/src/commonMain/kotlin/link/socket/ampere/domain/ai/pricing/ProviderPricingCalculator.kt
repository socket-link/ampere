package link.socket.ampere.domain.ai.pricing

internal object ProviderPricingCalculator {
    suspend fun estimateUsd(
        providerId: String,
        modelId: String,
        inputTokens: Int?,
        outputTokens: Int?,
    ): Double? {
        if (inputTokens == null || outputTokens == null) return null
        if (inputTokens < 0 || outputTokens < 0) return null

        val pricing = BundledProviderPricingCatalog.find(
            providerId = providerId,
            modelId = modelId,
        ) ?: return null

        val tier = pricing.tiers.firstOrNull { candidate ->
            candidate.maxInputTokens == null || inputTokens <= candidate.maxInputTokens
        } ?: return null

        return tier.inputUsdPerMillionTokens.costFor(inputTokens) +
            tier.outputUsdPerMillionTokens.costFor(outputTokens)
    }

    private fun Double.costFor(tokens: Int): Double = this * tokens.toDouble() / ONE_MILLION_TOKENS

    private const val ONE_MILLION_TOKENS = 1_000_000.0
}
