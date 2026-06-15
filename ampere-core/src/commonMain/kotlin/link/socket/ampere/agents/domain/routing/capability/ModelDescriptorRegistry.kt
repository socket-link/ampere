package link.socket.ampere.agents.domain.routing.capability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.domain.ai.provider.ProviderId
import link.socket.ampere.domain.limits.numericValue

/**
 * Lookup of [ModelDescriptor]s the relay consults to choose a model.
 *
 * The registry is separate from the hardcoded `AIModel.ALL_MODELS` lists so
 * platform modules can [register] additional descriptors at runtime (e.g. a
 * device-gated local model) without modifying the sealed model hierarchy.
 *
 * Keyed by `modelName` (= [AIModel.name]): selection is per-model because a tier
 * belongs to a model, not a provider (AMPR-214).
 */
interface ModelDescriptorRegistry {

    /** The descriptor for [modelName], or `null` if none is registered. */
    suspend fun descriptorFor(modelName: String): ModelDescriptor?

    /** Every registered descriptor. */
    suspend fun all(): List<ModelDescriptor>

    /** Register (or replace) the descriptor for its `modelName`. */
    suspend fun register(descriptor: ModelDescriptor)
}

/**
 * In-memory, mutex-guarded [ModelDescriptorRegistry]. Safe for concurrent
 * access from multiple reasoning sessions.
 *
 * Seeded by default with one descriptor per model across the bundled cloud
 * providers, each projected from the model's own metadata.
 */
class InMemoryModelDescriptorRegistry(
    seed: List<ModelDescriptor> = defaultModelDescriptors(),
) : ModelDescriptorRegistry {

    private val mutex = Mutex()
    private val descriptors: MutableMap<String, ModelDescriptor> =
        seed.associateByTo(mutableMapOf()) { it.modelName }

    override suspend fun descriptorFor(modelName: String): ModelDescriptor? =
        mutex.withLock { descriptors[modelName] }

    override suspend fun all(): List<ModelDescriptor> =
        mutex.withLock { descriptors.values.toList() }

    override suspend fun register(descriptor: ModelDescriptor) {
        mutex.withLock { descriptors[descriptor.modelName] = descriptor }
    }

    companion object {

        /**
         * Default capability set projected onto the bundled cloud models.
         * Capabilities are not carried by [link.socket.ampere.domain.ai.model.AIModelFeatures],
         * so this stays a flat per-model baseline (refined as routing matures);
         * the quality axes ([ModelDescriptor.reasoning],
         * [ModelDescriptor.supportedInputs], [ModelDescriptor.maxContextTokens])
         * *are* projected per-model from each model's own metadata.
         */
        private val CLOUD_MODEL_CAPABILITIES = setOf(
            ProviderCapability.WORLD_KNOWLEDGE,
            ProviderCapability.TOOL_CALLING,
            ProviderCapability.LONG_CONTEXT,
        )

        /**
         * Representative blended large-tier generation cost in USD per
         * normalized Watt (1 Watt = 1000 tokens), from June 2026 provider rates.
         * Google is cheapest and Anthropic priciest — a ~3.7× spread at the
         * large tier (the widest tier, per the AMPR-210 unit-economics
         * analysis). Cost stays provider-derived for now (AMPR-214): every model
         * inherits its owning provider's rate rather than authoring per-model
         * cost. These are provider *data*; cost-aware selection reads them and
         * never hardcodes them.
         */
        private const val GOOGLE_USD_PER_WATT: Double = 0.007
        private const val OPENAI_USD_PER_WATT: Double = 0.014
        private const val ANTHROPIC_USD_PER_WATT: Double = 0.026

        /**
         * Projects an [AIModel] into a [ModelDescriptor] from its own metadata:
         * reasoning and inputs come from [AIModelFeatures][link.socket.ampere.domain.ai.model.AIModelFeatures],
         * the context window from [ModelLimits][link.socket.ampere.domain.limits.ModelLimits],
         * and cost from the owning provider.
         */
        private fun AIModel.toModelDescriptor(
            providerId: ProviderId,
            costPerWatt: Double,
        ): ModelDescriptor = ModelDescriptor(
            modelName = name,
            providerId = providerId,
            capabilities = CLOUD_MODEL_CAPABILITIES,
            reasoning = features.reasoningLevel,
            maxContextTokens = limits.token.contextWindow.numericValue
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            supportedInputs = features.supportedInputs,
            cost = CostPolicy.Metered,
            costPerWatt = costPerWatt,
            availabilityGated = false,
        )

        /**
         * One descriptor per model across the three bundled cloud providers,
         * each projected from the model's own metadata.
         *
         * Computed on demand (not as a class-load constant). The model lists in
         * each `AIModel.*` companion are declared `by lazy` to break the JVM
         * class-init cycle: without lazy, if any data object (e.g.
         * `AIModel_Gemini.Flash_2_5`) were accessed before its companion
         * finished initializing, `<clinit>` would observe the object's `INSTANCE`
         * as null and permanently commit a list with null entries — causing an NPE
         * here. See the `ALL_MODELS` declarations in `AIModel_Claude` et al.
         */
        fun defaultModelDescriptors(): List<ModelDescriptor> {
            val modelsByProvider = listOf(
                Triple(AIProvider_Anthropic.id, AIModel_Claude.ALL_MODELS, ANTHROPIC_USD_PER_WATT),
                Triple(AIProvider_Google.id, AIModel_Gemini.ALL_MODELS, GOOGLE_USD_PER_WATT),
                Triple(AIProvider_OpenAI.id, AIModel_OpenAI.ALL_MODELS, OPENAI_USD_PER_WATT),
            )
            return modelsByProvider.flatMap { (providerId, models, costPerWatt) ->
                models.map { model -> model.toModelDescriptor(providerId, costPerWatt) }
            }
        }
    }
}
