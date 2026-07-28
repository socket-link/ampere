@file:Suppress("ClassName", "ObjectPropertyName", "ObjectPrivatePropertyName")

package link.socket.ampere.domain.ai.model

import io.ktor.util.date.GMTDate
import io.ktor.util.date.Month
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeSpeed
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.limits.ModelLimits
import link.socket.ampere.domain.limits.RateLimits
import link.socket.ampere.domain.limits.TokenCount
import link.socket.ampere.domain.limits.TokenLimits

/**
 * On-device models backing Rung 0 (AMPR-225): 0-Watt, local generation, no
 * network round-trip. Parallel to [AIModel_Claude]/[AIModel_Gemini]/
 * [AIModel_OpenAI] but never dials out — [AIProvider_OnDevice.client] exists
 * only to satisfy [link.socket.ampere.domain.ai.provider.AIProvider]'s shape
 * and is never invoked; execution is dispatched to a
 * [link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine]
 * instead (see [link.socket.ampere.llm.DispatchingUpstreamLlmClient]).
 */
sealed class AIModel_OnDevice(
    override val name: String,
    override val displayName: String,
    override val description: String,
    override val features: AIModelFeatures,
    override val limits: ModelLimits,
) : AIModel(name, displayName, description, features, limits) {

    /**
     * Apple Foundation Models' on-device model (iOS 26+ / Apple Intelligence).
     * Context window and reasoning level are provisional pending on-device
     * verification (AMPR-225 recon, §1.1) — update once confirmed against the
     * live `SystemLanguageModel`.
     */
    data object AppleFoundationModels : AIModel_OnDevice(
        name = APPLE_FOUNDATION_MODELS_NAME,
        displayName = APPLE_FOUNDATION_MODELS_DISPLAY_NAME,
        description = APPLE_FOUNDATION_MODELS_DESCRIPTION,
        features = APPLE_FOUNDATION_MODELS_FEATURES,
        limits = APPLE_FOUNDATION_MODELS_LIMITS,
    )

    companion object {
        /** Lazy per AMPR-218's class-init-cycle note on the cloud `ALL_MODELS` lists. */
        val ALL_MODELS: List<AIModel_OnDevice> by lazy { listOf(AppleFoundationModels) }
    }
}

private const val APPLE_FOUNDATION_MODELS_NAME = "apple-foundation-models-on-device"
private const val APPLE_FOUNDATION_MODELS_DISPLAY_NAME = "Apple Foundation Models (on-device)"
private const val APPLE_FOUNDATION_MODELS_DESCRIPTION =
    "Apple's on-device foundation model (Apple Intelligence). Runs entirely " +
        "locally with guided/structured generation; no network round-trip, 0W."

private val APPLE_FOUNDATION_MODELS_CUTOFF =
    GMTDate(year = 2025, month = Month.JUNE, dayOfMonth = 1, hours = 0, minutes = 0, seconds = 0)

private val APPLE_FOUNDATION_MODELS_FEATURES = AIModelFeatures(
    availableTools = emptyList(),
    reasoningLevel = RelativeReasoning.LOW,
    speed = RelativeSpeed.FAST,
    supportedInputs = SupportedInputs.TEXT,
    trainingCutoffDate = APPLE_FOUNDATION_MODELS_CUTOFF,
)

private val APPLE_FOUNDATION_MODELS_LIMITS = ModelLimits(
    rate = RateLimits(),
    token = TokenLimits(
        contextWindow = TokenCount._4096,
        maxOutput = TokenCount._4096,
    ),
)
