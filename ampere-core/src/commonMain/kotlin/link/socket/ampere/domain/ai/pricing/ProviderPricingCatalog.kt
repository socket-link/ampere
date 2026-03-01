package link.socket.ampere.domain.ai.pricing

import kotlinx.serialization.Serializable
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

@Serializable
data class ProviderPricingCatalog(
    val version: Int,
    val currency: String,
    val entries: List<ProviderModelPricing>,
)

@Serializable
data class ProviderModelPricing(
    val providerId: String,
    val modelId: String,
    val tiers: List<TokenPricingTier>,
)

@Serializable
data class TokenPricingTier(
    val maxInputTokens: Int? = null,
    val inputUsdPerMillionTokens: Double,
    val outputUsdPerMillionTokens: Double,
)

@OptIn(ExperimentalResourceApi::class)
internal object BundledProviderPricingCatalog {
    private const val RESOURCE_PATH = "files/provider_pricing.v1.json"

    private var cachedCatalog: IndexedProviderPricingCatalog? = null

    suspend fun load(): ProviderPricingCatalog = loadIndexedCatalog().catalog

    suspend fun find(providerId: String, modelId: String): ProviderModelPricing? =
        loadIndexedCatalog().entriesByKey[ProviderModelKey(providerId = providerId, modelId = modelId)]

    private suspend fun loadIndexedCatalog(): IndexedProviderPricingCatalog {
        cachedCatalog?.let { return it }

        val json = Res.readBytes(RESOURCE_PATH).decodeToString()
        val catalog = DEFAULT_JSON.decodeFromString(ProviderPricingCatalog.serializer(), json)
        return IndexedProviderPricingCatalog(
            catalog = catalog,
            entriesByKey = catalog.entries.associateBy {
                ProviderModelKey(
                    providerId = it.providerId,
                    modelId = it.modelId,
                )
            },
        ).also { cachedCatalog = it }
    }
}

private data class IndexedProviderPricingCatalog(
    val catalog: ProviderPricingCatalog,
    val entriesByKey: Map<ProviderModelKey, ProviderModelPricing>,
)

private data class ProviderModelKey(
    val providerId: String,
    val modelId: String,
)
