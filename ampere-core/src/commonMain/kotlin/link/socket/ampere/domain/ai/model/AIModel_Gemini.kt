@file:Suppress("ClassName", "ObjectPropertyName", "ObjectPrivatePropertyName", "ConstPropertyName")

package link.socket.ampere.domain.ai.model

import io.ktor.util.date.GMTDate
import io.ktor.util.date.Month
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeSpeed
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.limits.ModelLimits
import link.socket.ampere.domain.limits.RateLimitsFactory
import link.socket.ampere.domain.limits.TokenCount
import link.socket.ampere.domain.limits.TokenLimits
import link.socket.ampere.domain.tool.AITool_Gemini
import link.socket.ampere.domain.tool.ProvidedTool

sealed class AIModel_Gemini(
    override val name: String,
    override val displayName: String,
    override val description: String,
    override val features: AIModelFeatures,
    override val limits: ModelLimits,
) : AIModel(name, displayName, description, features, limits) {

    data object Pro_2_5 : AIModel_Gemini(
        name = _2_5_Pro_NAME,
        displayName = _2_5_Pro_DISPLAY_NAME,
        description = _2_5_Pro_DESCRIPTION,
        features = _2_5_Pro_FEATURES,
        limits = _2_5_Pro_LIMITS,
    )

    data object Flash_2_5 : AIModel_Gemini(
        name = _2_5_Flash_NAME,
        displayName = _2_5_Flash_DISPLAY_NAME,
        description = _2_5_Flash_DESCRIPTION,
        features = _2_5_Flash_FEATURES,
        limits = _2_5_Flash_LIMITS,
    )

    data object Flash_Lite_2_5 : AIModel_Gemini(
        name = _2_5_Flash_Lite_NAME,
        displayName = _2_5_Flash_Lite_DISPLAY_NAME,
        description = _2_5_Flash_Lite_DESCRIPTION,
        features = _2_5_Flash_Lite_FEATURES,
        limits = _2_5_Flash_Lite_LIMITS,
    )

    data object Flash_2_0 : AIModel_Gemini(
        name = _2_0_Flash_NAME,
        displayName = _2_0_Flash_DISPLAY_NAME,
        description = _2_0_Flash_DESCRIPTION,
        features = _2_0_Flash_FEATURES,
        limits = _2_0_Flash_LIMITS,
    )

    data object Flash_Lite_2_0 : AIModel_Gemini(
        name = _2_0_Flash_Lite_NAME,
        displayName = _2_0_Flash_Lite_DISPLAY_NAME,
        description = _2_0_Flash_Lite_DESCRIPTION,
        features = _2_0_Flash_Lite_FEATURES,
        limits = _2_0_Flash_Lite_LIMITS,
    )

    data object Pro_3_1_Preview : AIModel_Gemini(
        name = _3_1_Pro_Preview_NAME,
        displayName = _3_1_Pro_Preview_DISPLAY_NAME,
        description = _3_1_Pro_Preview_DESCRIPTION,
        features = _3_1_Pro_Preview_FEATURES,
        limits = _3_1_Pro_Preview_LIMITS,
    )

    data object Flash_3_8 : AIModel_Gemini(
        name = _3_8_Flash_NAME,
        displayName = _3_8_Flash_DISPLAY_NAME,
        description = _3_8_Flash_DESCRIPTION,
        features = _3_8_Flash_FEATURES,
        limits = _3_8_Flash_LIMITS,
    )

    /**
     * A Gemini model Ampere has no bundled entry for, constructed by ID so a
     * consumer can roll to a new model without waiting on an Ampere release.
     *
     * Not part of [ALL_MODELS], so it has no bundled capability rung or
     * pricing; register a
     * [ModelDescriptor][link.socket.ampere.agents.domain.routing.capability.ModelDescriptor]
     * for [name] if the relay should route to it.
     */
    data class Custom(
        override val name: String,
        override val features: AIModelFeatures,
        override val limits: ModelLimits,
        override val displayName: String = name,
        override val description: String = "",
    ) : AIModel_Gemini(name, displayName, description, features, limits)

    companion object Companion {

        // ---- Tools ----

        private val _2_5_Pro_TOOLS: List<ProvidedTool<AITool_Gemini>> = listOf(
            ProvidedTool.CodeExecution(AITool_Gemini.CodeExecution),
            ProvidedTool.UrlContext(AITool_Gemini.UrlContext),
            ProvidedTool.WebSearch(AITool_Gemini.WebSearch),
        )

        private val _2_5_Flash_TOOLS: List<ProvidedTool<AITool_Gemini>> = _2_5_Pro_TOOLS
        private val _2_5_Flash_Lite_TOOLS: List<ProvidedTool<AITool_Gemini>> = _2_5_Flash_TOOLS

        private val _2_0_Flash_TOOLS: List<ProvidedTool<AITool_Gemini>> = listOf(
            ProvidedTool.CodeExecution(AITool_Gemini.CodeExecution),
            ProvidedTool.WebSearch(AITool_Gemini.WebSearch),
        )

        private val _2_0_Flash_Lite_TOOLS = emptyList<ProvidedTool<AITool_Gemini>>()

        private val _3_1_Pro_Preview_TOOLS: List<ProvidedTool<AITool_Gemini>> = _2_5_Pro_TOOLS
        private val _3_8_Flash_TOOLS: List<ProvidedTool<AITool_Gemini>> = _2_5_Pro_TOOLS

        // ---- Rate Limits ----

        private const val TIER_FREE_2_5_PRO_RPM = 5
        private const val TIER_FREE_2_5_PRO_RPD = 100
        private const val TIER_1_2_5_PRO_RPM = 150
        private const val TIER_1_2_5_PRO_RPD = 10000
        private const val TIER_2_2_5_PRO_RPM = 1000
        private const val TIER_2_2_5_PRO_RPD = 50000
        private const val TIER_3_2_5_PRO_RPM = 2000
        private val TIER_3_2_5_PRO_RPD = null

        private const val TIER_FREE_2_5_FLASH_RPM = 10
        private const val TIER_FREE_2_5_FLASH_RPD = 250
        private const val TIER_1_2_5_FLASH_RPM = 1000
        private const val TIER_1_2_5_FLASH_RPD = 10000
        private const val TIER_2_2_5_FLASH_RPM = 2000
        private const val TIER_2_2_5_FLASH_RPD = 100000
        private const val TIER_3_2_5_FLASH_RPM = 10000
        private val TIER_3_2_5_FLASH_RPD = null

        private const val TIER_FREE_2_5_FLASH_LITE_RPM = 15
        private const val TIER_FREE_2_5_FLASH_LITE_RPD = 1000
        private const val TIER_1_2_5_FLASH_LITE_RPM = 4000
        private val TIER_1_2_5_FLASH_LITE_RPD = null
        private const val TIER_2_2_5_FLASH_LITE_RPM = 10000
        private val TIER_2_2_5_FLASH_LITE_RPD = null
        private const val TIER_3_2_5_FLASH_LITE_RPM = 30000
        private val TIER_3_2_5_FLASH_LITE_RPD = null

        private const val TIER_FREE_2_0_FLASH_RPM = 15
        private const val TIER_FREE_2_0_FLASH_RPD = 200
        private const val TIER_1_2_0_FLASH_RPM = 2000
        private val TIER_1_2_0_FLASH_RPD = null
        private const val TIER_2_2_0_FLASH_RPM = 10000
        private val TIER_2_2_0_FLASH_RPD = null
        private const val TIER_3_2_0_FLASH_RPM = 30000
        private val TIER_3_2_0_FLASH_RPD = null

        private const val TIER_FREE_2_0_FLASH_LITE_RPM = 30
        private const val TIER_FREE_2_0_FLASH_LITE_RPD = 200
        private const val TIER_1_2_0_FLASH_LITE_RPM = 4000
        private val TIER_1_2_0_FLASH_LITE_RPD = null
        private const val TIER_2_2_0_FLASH_LITE_RPM = 20000
        private val TIER_2_2_0_FLASH_LITE_RPD = null
        private const val TIER_3_2_0_FLASH_LITE_RPM = 30000
        private val TIER_3_2_0_FLASH_LITE_RPD = null

        private val _2_5_Pro_RateLimitsFactory = RateLimitsFactory(
            tierFreeRequestLimits = Pair(TIER_FREE_2_5_PRO_RPM, TIER_FREE_2_5_PRO_RPD),
            tier1RequestLimits = Pair(TIER_1_2_5_PRO_RPM, TIER_1_2_5_PRO_RPD),
            tier2RequestLimits = Pair(TIER_2_2_5_PRO_RPM, TIER_2_2_5_PRO_RPD),
            tier3RequestLimits = Pair(TIER_3_2_5_PRO_RPM, TIER_3_2_5_PRO_RPD),
        )

        private val _2_5_Flash_RateLimitsFactory = RateLimitsFactory(
            tierFreeRequestLimits = Pair(TIER_FREE_2_5_FLASH_RPM, TIER_FREE_2_5_FLASH_RPD),
            tier1RequestLimits = Pair(TIER_1_2_5_FLASH_RPM, TIER_1_2_5_FLASH_RPD),
            tier2RequestLimits = Pair(TIER_2_2_5_FLASH_RPM, TIER_2_2_5_FLASH_RPD),
            tier3RequestLimits = Pair(TIER_3_2_5_FLASH_RPM, TIER_3_2_5_FLASH_RPD),
        )

        private val _2_5_Flash_Lite_RateLimitsFactory = RateLimitsFactory(
            tierFreeRequestLimits = Pair(TIER_FREE_2_5_FLASH_LITE_RPM, TIER_FREE_2_5_FLASH_LITE_RPD),
            tier1RequestLimits = Pair(TIER_1_2_5_FLASH_LITE_RPM, TIER_1_2_5_FLASH_LITE_RPD),
            tier2RequestLimits = Pair(TIER_2_2_5_FLASH_LITE_RPM, TIER_2_2_5_FLASH_LITE_RPD),
            tier3RequestLimits = Pair(TIER_3_2_5_FLASH_LITE_RPM, TIER_3_2_5_FLASH_LITE_RPD),
        )

        private val _2_0_Flash_RateLimitsFactory = RateLimitsFactory(
            tierFreeRequestLimits = Pair(TIER_FREE_2_0_FLASH_RPM, TIER_FREE_2_0_FLASH_RPD),
            tier1RequestLimits = Pair(TIER_1_2_0_FLASH_RPM, TIER_1_2_0_FLASH_RPD),
            tier2RequestLimits = Pair(TIER_2_2_0_FLASH_RPM, TIER_2_2_0_FLASH_RPD),
            tier3RequestLimits = Pair(TIER_3_2_0_FLASH_RPM, TIER_3_2_0_FLASH_RPD),
        )

        private val _2_0_Flash_Lite_RateLimitsFactory = RateLimitsFactory(
            tierFreeRequestLimits = Pair(TIER_FREE_2_0_FLASH_LITE_RPM, TIER_FREE_2_0_FLASH_LITE_RPD),
            tier1RequestLimits = Pair(TIER_1_2_0_FLASH_LITE_RPM, TIER_1_2_0_FLASH_LITE_RPD),
            tier2RequestLimits = Pair(TIER_2_2_0_FLASH_LITE_RPM, TIER_2_2_0_FLASH_LITE_RPD),
            tier3RequestLimits = Pair(TIER_3_2_0_FLASH_LITE_RPM, TIER_3_2_0_FLASH_LITE_RPD),
        )

        private val _2_5_Pro_RATE_LIMITS = _2_5_Pro_RateLimitsFactory.createRateLimits(
            tierFreeTPM = TokenCount._250k,
            tier1TPM = TokenCount._2m,
            tier2TPM = TokenCount._5m,
            tier3TPM = TokenCount._8m,
        )

        private val _2_5_Flash_RATE_LIMITS = _2_5_Flash_RateLimitsFactory.createRateLimits(
            tierFreeTPM = TokenCount._250k,
            tier1TPM = TokenCount._1m,
            tier2TPM = TokenCount._3m,
            tier3TPM = TokenCount._8m,
        )

        private val _2_5_Flash_Lite_RATE_LIMITS = _2_5_Flash_Lite_RateLimitsFactory.createRateLimits(
            tierFreeTPM = TokenCount._250k,
            tier1TPM = TokenCount._4m,
            tier2TPM = TokenCount._10m,
            tier3TPM = TokenCount._30m,
        )

        private val _2_0_Flash_RATE_LIMITS = _2_0_Flash_RateLimitsFactory.createRateLimits(
            tierFreeTPM = TokenCount._1m,
            tier1TPM = TokenCount._4m,
            tier2TPM = TokenCount._10m,
            tier3TPM = TokenCount._30m,
        )

        private val _2_0_Flash_Lite_RATE_LIMITS = _2_0_Flash_Lite_RateLimitsFactory.createRateLimits(
            tierFreeTPM = TokenCount._1m,
            tier1TPM = TokenCount._4m,
            tier2TPM = TokenCount._10m,
            tier3TPM = TokenCount._30m,
        )

        // Google no longer publishes per-tier interactive limits for Gemini 3.x
        // (they live in AI Studio), so these carry the 2.5 Pro / Flash figures.
        private val _3_1_Pro_Preview_RATE_LIMITS = _2_5_Pro_RATE_LIMITS
        private val _3_8_Flash_RATE_LIMITS = _2_5_Flash_RATE_LIMITS

        // ---- Token Limits ----

        private val CONTEXT_WINDOW_TOKENS = TokenCount._1m

        private val _2_5_Pro_TOKEN_LIMITS = TokenLimits(
            contextWindow = CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._64k,
        )

        private val _2_5_Flash_TOKEN_LIMITS = _2_5_Pro_TOKEN_LIMITS
        private val _2_5_Flash_Lite_TOKEN_LIMITS = _2_5_Pro_TOKEN_LIMITS

        private val _2_0_Flash_TOKEN_LIMITS = TokenLimits(
            contextWindow = CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._8192,
        )

        private val _2_0_Flash_Lite_TOKEN_LIMITS = _2_0_Flash_TOKEN_LIMITS

        private val _3_1_Pro_Preview_TOKEN_LIMITS = TokenLimits(
            contextWindow = CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._64k,
        )

        private val _3_8_Flash_TOKEN_LIMITS = _3_1_Pro_Preview_TOKEN_LIMITS

        // ---- Limits ----

        private val _2_5_Pro_LIMITS = ModelLimits(
            rate = _2_5_Pro_RATE_LIMITS,
            token = _2_5_Pro_TOKEN_LIMITS,
        )

        private val _2_5_Flash_LIMITS = ModelLimits(
            rate = _2_5_Flash_RATE_LIMITS,
            token = _2_5_Flash_TOKEN_LIMITS,
        )

        private val _2_5_Flash_Lite_LIMITS = ModelLimits(
            rate = _2_5_Flash_Lite_RATE_LIMITS,
            token = _2_5_Flash_Lite_TOKEN_LIMITS,
        )

        private val _2_0_Flash_LIMITS = ModelLimits(
            rate = _2_0_Flash_RATE_LIMITS,
            token = _2_0_Flash_TOKEN_LIMITS,
        )

        private val _2_0_Flash_Lite_LIMITS = ModelLimits(
            rate = _2_0_Flash_Lite_RATE_LIMITS,
            token = _2_0_Flash_Lite_TOKEN_LIMITS,
        )

        private val _3_1_Pro_Preview_LIMITS = ModelLimits(
            rate = _3_1_Pro_Preview_RATE_LIMITS,
            token = _3_1_Pro_Preview_TOKEN_LIMITS,
        )

        private val _3_8_Flash_LIMITS = ModelLimits(
            rate = _3_8_Flash_RATE_LIMITS,
            token = _3_8_Flash_TOKEN_LIMITS,
        )

        // ---- Training Cutoffs ----

        private val _2_5_Pro_CUTOFF = GMTDate(
            year = 2025,
            month = Month.JANUARY,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val _2_5_Flash_CUTOFF = _2_5_Pro_CUTOFF
        private val _2_5_Flash_Lite_CUTOFF = _2_5_Flash_CUTOFF

        private val _2_0_Flash_CUTOFF = GMTDate(
            year = 2024,
            month = Month.AUGUST,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val _2_0_Flash_Lite_CUTOFF = _2_0_Flash_CUTOFF

        // Google's Gemini 3 guide gives January 2025 for "Gemini 3 models"; the
        // 3.8 Flash model page lists no cutoff of its own.
        private val _3_1_Pro_Preview_CUTOFF = GMTDate(
            year = 2025,
            month = Month.JANUARY,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val _3_8_Flash_CUTOFF = _3_1_Pro_Preview_CUTOFF

        // --- Supported Inputs ----

        private val _2_5_Pro_SUPPORTED_INPUTS = SupportedInputs.ALL
        private val _2_5_Flash_SUPPORTED_INPUTS = SupportedInputs(
            audio = true,
            image = true,
            text = true,
            video = true,
        )
        private val _2_5_Flash_Lite_SUPPORTED_INPUTS = _2_5_Pro_SUPPORTED_INPUTS
        private val _2_0_Flash_SUPPORTED_INPUTS = _2_5_Flash_SUPPORTED_INPUTS
        private val _2_0_Flash_Lite_SUPPORTED_INPUTS = _2_0_Flash_SUPPORTED_INPUTS

        private val _3_1_Pro_Preview_SUPPORTED_INPUTS = SupportedInputs.ALL
        private val _3_8_Flash_SUPPORTED_INPUTS = SupportedInputs.ALL

        // ---- Model Features ----

        private val _2_5_Pro_FEATURES = AIModelFeatures(
            availableTools = _2_5_Pro_TOOLS,
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            supportedInputs = _2_5_Pro_SUPPORTED_INPUTS,
            trainingCutoffDate = _2_5_Pro_CUTOFF,
        )

        private val _2_5_Flash_FEATURES = AIModelFeatures(
            availableTools = _2_5_Flash_TOOLS,
            reasoningLevel = RelativeReasoning.NORMAL,
            speed = RelativeSpeed.NORMAL,
            supportedInputs = _2_5_Flash_SUPPORTED_INPUTS,
            trainingCutoffDate = _2_5_Flash_CUTOFF,
        )

        private val _2_5_Flash_Lite_FEATURES = AIModelFeatures(
            availableTools = _2_5_Flash_Lite_TOOLS,
            reasoningLevel = RelativeReasoning.LOW,
            speed = RelativeSpeed.FAST,
            supportedInputs = _2_5_Flash_Lite_SUPPORTED_INPUTS,
            trainingCutoffDate = _2_5_Flash_Lite_CUTOFF,
        )

        private val _2_0_Flash_FEATURES = AIModelFeatures(
            availableTools = _2_0_Flash_TOOLS,
            reasoningLevel = RelativeReasoning.NORMAL,
            speed = RelativeSpeed.FAST,
            supportedInputs = _2_0_Flash_SUPPORTED_INPUTS,
            trainingCutoffDate = _2_0_Flash_CUTOFF,
        )

        private val _2_0_Flash_Lite_FEATURES = AIModelFeatures(
            availableTools = _2_0_Flash_Lite_TOOLS,
            reasoningLevel = RelativeReasoning.LOW,
            speed = RelativeSpeed.FAST,
            supportedInputs = _2_0_Flash_Lite_SUPPORTED_INPUTS,
            trainingCutoffDate = _2_0_Flash_Lite_CUTOFF,
        )

        private val _3_1_Pro_Preview_FEATURES = AIModelFeatures(
            availableTools = _3_1_Pro_Preview_TOOLS,
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            supportedInputs = _3_1_Pro_Preview_SUPPORTED_INPUTS,
            trainingCutoffDate = _3_1_Pro_Preview_CUTOFF,
        )

        private val _3_8_Flash_FEATURES = AIModelFeatures(
            availableTools = _3_8_Flash_TOOLS,
            reasoningLevel = RelativeReasoning.NORMAL,
            speed = RelativeSpeed.FAST,
            supportedInputs = _3_8_Flash_SUPPORTED_INPUTS,
            trainingCutoffDate = _3_8_Flash_CUTOFF,
        )

        // ---- Model Names ----

        private const val _2_5_Pro_NAME = "gemini-2.5-pro"
        private const val _2_5_Pro_DISPLAY_NAME = "Gemini 2.5 Pro"
        private const val _2_5_Pro_DESCRIPTION =
            "Gemini 2.5 Pro is our state-of-the-art thinking model, capable " +
                "of reasoning over complex problems in code, math, and STEM, " +
                "as well as analyzing large datasets, codebases, and documents using long context."

        private const val _2_5_Flash_NAME = "gemini-2.5-flash"
        private const val _2_5_Flash_DISPLAY_NAME = "Gemini 2.5 Flash"
        private const val _2_5_Flash_DESCRIPTION =
            "Our best model in terms of price-performance, offering " +
                "well-rounded capabilities. 2.5 Flash is best for large scale " +
                "processing, low-latency, high volume tasks that require thinking, and agentic use cases."

        private const val _2_5_Flash_Lite_NAME = "gemini-2.5-flash-lite"
        private const val _2_5_Flash_Lite_DISPLAY_NAME = "Gemini 2.5 Flash Lite"
        private const val _2_5_Flash_Lite_DESCRIPTION =
            "A Gemini 2.5 Flash model optimized for cost-efficiency and " +
                "high throughput."

        private const val _2_0_Flash_NAME = "gemini-2.0-flash"
        private const val _2_0_Flash_DISPLAY_NAME = "Gemini 2.0 Flash"
        private const val _2_0_Flash_DESCRIPTION =
            "Gemini 2.0 Flash delivers next-gen features and improved " +
                "capabilities, including superior speed, native tool use, and a 1M token context window."

        private const val _2_0_Flash_Lite_NAME = "gemini-2.0-flash-lite"
        private const val _2_0_Flash_Lite_DISPLAY_NAME = "Gemini 2.0 Flash Lite"
        private const val _2_0_Flash_Lite_DESCRIPTION =
            "A Gemini 2.0 Flash model optimized for cost efficiency and " +
                "low latency."

        private const val _3_1_Pro_Preview_NAME = "gemini-3.1-pro-preview"
        private const val _3_1_Pro_Preview_DISPLAY_NAME = "Gemini 3.1 Pro (Preview)"
        private const val _3_1_Pro_Preview_DESCRIPTION =
            "Google's most intelligent model, with state-of-the-art reasoning " +
                "and multimodal understanding for complex agentic and coding " +
                "tasks. Features a 1M token context window."

        private const val _3_8_Flash_NAME = "gemini-3.8-flash"
        private const val _3_8_Flash_DISPLAY_NAME = "Gemini 3.8 Flash"
        private const val _3_8_Flash_DESCRIPTION =
            "Frontier-class performance at Flash speed and cost, for " +
                "high-volume, low-latency, and agentic workloads. Features a " +
                "1M token context window."

        // ---- Models ----

        // Lazy to avoid the JVM class-init cycle: if any data object (Pro_2_5, etc.)
        // is accessed before this companion finishes initializing, the companion's
        // <clinit> would see that object's INSTANCE as null and commit a list with
        // null entries. Lazy defers evaluation until the companion is fully set up.
        val ALL_MODELS by lazy {
            listOf(
                Pro_2_5,
                Flash_2_5,
                Flash_Lite_2_5,
                Flash_2_0,
                Flash_Lite_2_0,
                Pro_3_1_Preview,
                Flash_3_8,
            )
        }
    }
}
