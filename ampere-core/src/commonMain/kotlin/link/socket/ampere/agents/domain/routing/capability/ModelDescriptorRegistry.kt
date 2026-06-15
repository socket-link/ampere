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
         * Rung assignments for every model in the bundled cloud catalogs.
         * Update this map when a new frontier model ships — no other file needs to change.
         *
         * Rungs are assigned by generation and capability tier, consistent with
         * each model's [AIModelFeatures.reasoningLevel] (no LOW-reasoning model
         * exceeds TWO; no FOUR-rung model is below HIGH).
         *
         * ONE  — entry/cheap (LOW reasoning, nano/lite variants)
         * TWO  — standard (NORMAL reasoning, fast mid-tier)
         * THREE — advanced (capable HIGH or premium NORMAL)
         * FOUR — frontier (flagship HIGH, latest generation)
         */
        private val MODEL_RUNGS: Map<String, CapabilityRung> = mapOf(
            // ── Anthropic / Claude ────────────────────────────────────────────
            "claude-3-haiku-20240307" to CapabilityRung.ONE,
            "claude-3-5-haiku-latest" to CapabilityRung.TWO,
            "claude-haiku-4-5" to CapabilityRung.TWO,
            "claude-sonnet-4-0" to CapabilityRung.THREE,
            "claude-3-7-sonnet-latest" to CapabilityRung.THREE,
            "claude-sonnet-4-5-20250929" to CapabilityRung.THREE,
            "claude-opus-4-0" to CapabilityRung.FOUR,
            "claude-opus-4-1" to CapabilityRung.FOUR,
            "claude-opus-4-5-20251101" to CapabilityRung.FOUR,
            // ── OpenAI / GPT ──────────────────────────────────────────────────
            "gpt-5-nano" to CapabilityRung.ONE,
            "gpt-4o-mini" to CapabilityRung.ONE,
            "gpt-5-mini" to CapabilityRung.TWO,
            "gpt-4.1-mini" to CapabilityRung.TWO,
            "gpt-4o" to CapabilityRung.TWO,
            "o3-mini" to CapabilityRung.TWO,
            "gpt-4.1" to CapabilityRung.THREE,
            "o4-mini" to CapabilityRung.THREE,
            "gpt-5" to CapabilityRung.FOUR,
            "gpt-5.1" to CapabilityRung.FOUR,
            "gpt-5.1-chat-latest" to CapabilityRung.FOUR,
            "gpt-5.1-codex-max" to CapabilityRung.FOUR,
            "o3" to CapabilityRung.FOUR,
            // ── Google / Gemini ───────────────────────────────────────────────
            "gemini-2.0-flash-lite" to CapabilityRung.ONE,
            "gemini-2.5-flash-lite" to CapabilityRung.ONE,
            "gemini-2.0-flash" to CapabilityRung.TWO,
            "gemini-2.5-flash" to CapabilityRung.TWO,
            "gemini-2.5-pro" to CapabilityRung.THREE,
            "gemini-3-pro-latest" to CapabilityRung.FOUR,
        )

        /**
         * Projects an [AIModel] into a [ModelDescriptor] from its own metadata:
         * reasoning and inputs come from [AIModelFeatures][link.socket.ampere.domain.ai.model.AIModelFeatures],
         * the context window from [ModelLimits][link.socket.ampere.domain.limits.ModelLimits],
         * cost from the owning provider, and the rung from [MODEL_RUNGS].
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
            rung = MODEL_RUNGS[name] ?: CapabilityRung.ONE,
        )

        /**
         * One descriptor per model across the three bundled cloud providers,
         * each projected from the model's own metadata.
         *
         * Computed on demand (not as a class-load constant) so the bundled
         * `AIProvider`/`AIModel` data-object graph is fully initialized before it
         * is read. The model lists are taken straight from each `AIModel.*`
         * companion rather than via `AIProvider.availableModels`: reading the
         * list through a provider data object initializes it reentrantly
         * (`AIProvider_*.<clinit>` -> `AIModel_*.<clinit>`), which yields null
         * model entries. Touching the model companions first sidesteps that
         * cycle.
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
