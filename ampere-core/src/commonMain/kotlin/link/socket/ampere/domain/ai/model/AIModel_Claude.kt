@file:Suppress("ClassName", "ObjectPropertyName", "ObjectPrivatePropertyName")

package link.socket.ampere.domain.ai.model

import io.ktor.util.date.GMTDate
import io.ktor.util.date.Month
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeSpeed
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs.Companion.TEXT_AND_IMAGE
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs.Companion.TEXT_IMAGE_AND_PDF
import link.socket.ampere.domain.limits.ModelLimits
import link.socket.ampere.domain.limits.RateLimitsFactory
import link.socket.ampere.domain.limits.TokenCount
import link.socket.ampere.domain.limits.TokenLimits
import link.socket.ampere.domain.tool.AITool_Claude
import link.socket.ampere.domain.tool.ProvidedTool

sealed class AIModel_Claude(
    override val name: String,
    override val displayName: String,
    override val description: String,
    override val features: AIModelFeatures,
    override val limits: ModelLimits,
) : AIModel(name, displayName, description, features, limits) {

    data object Opus_4_1 : AIModel_Claude(
        name = Opus_4_1_NAME,
        displayName = Opus_4_1_DISPLAY_NAME,
        description = Opus_4_1_DESCRIPTION,
        features = Opus_4_1_FEATURES,
        limits = Opus_4_1_LIMITS,
    )

    data object Opus_4 : AIModel_Claude(
        name = Opus_4_NAME,
        displayName = Opus_4_DISPLAY_NAME,
        description = Opus_4_DESCRIPTION,
        features = Opus_4_FEATURES,
        limits = Opus_4_LIMITS,
    )

    /** Retired by Anthropic on 2026-06-15; requests for `claude-sonnet-4-0` fail. Use [Sonnet_5]. */
    data object Sonnet_4 : AIModel_Claude(
        name = Sonnet_4_NAME,
        displayName = Sonnet_4_DISPLAY_NAME,
        description = Sonnet_4_DESCRIPTION,
        features = featuresForSonnet(
            availableTools = Sonnet_4_TOOLS,
            supportedInputs = Sonnet_4_SUPPORTED_INPUTS,
            cutoffDate = Sonnet_4_CUTOFF,
        ),
        limits = Sonnet_4_LIMITS,
    )

    data object Sonnet_3_7 : AIModel_Claude(
        name = Sonnet_3_7_NAME,
        displayName = Sonnet_3_7_DISPLAY_NAME,
        description = Sonnet_3_7_DESCRIPTION,
        features = featuresForSonnet(
            availableTools = Sonnet_3_7_TOOLS,
            supportedInputs = Sonnet_3_7_SUPPORTED_INPUTS,
            cutoffDate = Sonnet_3_7_CUTOFF,
        ),
        limits = Sonnet_3_7_LIMITS,
    )

    data object Haiku_3_5 : AIModel_Claude(
        name = Haiku_3_5_NAME,
        displayName = Haiku_3_5_DISPLAY_NAME,
        description = Haiku_3_5_DESCRIPTION,
        features = featuresForHaiku(
            availableTools = Haiku_3_5_TOOLS,
            supportedInputs = Haiku_3_5_SUPPORTED_INPUTS,
            cutoffDate = Haiku_3_5_CUTOFF,
        ),
        limits = Haiku_3_5_LIMITS,
    )

    data object Haiku_3 : AIModel_Claude(
        name = Haiku_3_NAME,
        displayName = Haiku_3_DISPLAY_NAME,
        description = Haiku_3_DESCRIPTION,
        features = Haiku_3_FEATURES,
        limits = Haiku_3_LIMITS,
    )

    data object Opus_4_5 : AIModel_Claude(
        name = Opus_4_5_NAME,
        displayName = Opus_4_5_DISPLAY_NAME,
        description = Opus_4_5_DESCRIPTION,
        features = Opus_4_5_FEATURES,
        limits = Opus_4_5_LIMITS,
    )

    data object Sonnet_4_5 : AIModel_Claude(
        name = Sonnet_4_5_NAME,
        displayName = Sonnet_4_5_DISPLAY_NAME,
        description = Sonnet_4_5_DESCRIPTION,
        features = featuresForSonnet(
            availableTools = Sonnet_4_5_TOOLS,
            supportedInputs = Sonnet_4_5_SUPPORTED_INPUTS,
            cutoffDate = Sonnet_4_5_CUTOFF,
        ),
        limits = Sonnet_4_5_LIMITS,
    )

    data object Haiku_4_5 : AIModel_Claude(
        name = Haiku_4_5_NAME,
        displayName = Haiku_4_5_DISPLAY_NAME,
        description = Haiku_4_5_DESCRIPTION,
        features = featuresForHaiku(
            availableTools = Haiku_4_5_TOOLS,
            supportedInputs = Haiku_4_5_SUPPORTED_INPUTS,
            cutoffDate = Haiku_4_5_CUTOFF,
        ),
        limits = Haiku_4_5_LIMITS,
    )

    // ---- 4.6 generation onward: dateless IDs are pinned snapshots, not aliases ----

    data object Opus_4_6 : AIModel_Claude(
        name = Opus_4_6_NAME,
        displayName = Opus_4_6_DISPLAY_NAME,
        description = Opus_4_6_DESCRIPTION,
        features = currentGenerationFeatures(
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            cutoffDate = Opus_4_6_CUTOFF,
            supportsSamplingParameters = true,
        ),
        limits = CURRENT_GENERATION_OPUS_LIMITS,
    )

    data object Opus_4_7 : AIModel_Claude(
        name = Opus_4_7_NAME,
        displayName = Opus_4_7_DISPLAY_NAME,
        description = Opus_4_7_DESCRIPTION,
        features = currentGenerationFeatures(
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            cutoffDate = Opus_4_7_CUTOFF,
            supportsSamplingParameters = false,
        ),
        limits = CURRENT_GENERATION_OPUS_LIMITS,
    )

    data object Opus_4_8 : AIModel_Claude(
        name = Opus_4_8_NAME,
        displayName = Opus_4_8_DISPLAY_NAME,
        description = Opus_4_8_DESCRIPTION,
        features = currentGenerationFeatures(
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            cutoffDate = Opus_4_8_CUTOFF,
            supportsSamplingParameters = false,
        ),
        limits = CURRENT_GENERATION_OPUS_LIMITS,
    )

    data object Opus_5 : AIModel_Claude(
        name = Opus_5_NAME,
        displayName = Opus_5_DISPLAY_NAME,
        description = Opus_5_DESCRIPTION,
        features = currentGenerationFeatures(
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.NORMAL,
            cutoffDate = Opus_5_CUTOFF,
            supportsSamplingParameters = false,
        ),
        limits = CURRENT_GENERATION_OPUS_LIMITS,
    )

    data object Sonnet_4_6 : AIModel_Claude(
        name = Sonnet_4_6_NAME,
        displayName = Sonnet_4_6_DISPLAY_NAME,
        description = Sonnet_4_6_DESCRIPTION,
        features = currentGenerationFeatures(
            reasoningLevel = RelativeReasoning.NORMAL,
            speed = RelativeSpeed.NORMAL,
            cutoffDate = Sonnet_4_6_CUTOFF,
            supportsSamplingParameters = true,
        ),
        limits = CURRENT_GENERATION_SONNET_LIMITS,
    )

    data object Sonnet_5 : AIModel_Claude(
        name = Sonnet_5_NAME,
        displayName = Sonnet_5_DISPLAY_NAME,
        description = Sonnet_5_DESCRIPTION,
        features = currentGenerationFeatures(
            reasoningLevel = RelativeReasoning.NORMAL,
            speed = RelativeSpeed.FAST,
            cutoffDate = Sonnet_5_CUTOFF,
            supportsSamplingParameters = false,
        ),
        limits = CURRENT_GENERATION_SONNET_LIMITS,
    )

    data object Fable_5_1 : AIModel_Claude(
        name = Fable_5_1_NAME,
        displayName = Fable_5_1_DISPLAY_NAME,
        description = Fable_5_1_DESCRIPTION,
        features = currentGenerationFeatures(
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            cutoffDate = Fable_5_1_CUTOFF,
            supportsSamplingParameters = false,
        ),
        limits = CURRENT_GENERATION_OPUS_LIMITS,
    )

    /**
     * A Claude model Ampere has no bundled entry for, constructed by ID so a
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
    ) : AIModel_Claude(name, displayName, description, features, limits)

    companion object Companion {

        // ---- Tools ----

        private val Opus_4_1_TOOLS: List<ProvidedTool<AITool_Claude>> = listOf(
            ProvidedTool.Bash(AITool_Claude.Bash),
            ProvidedTool.CodeExecution(AITool_Claude.CodeExecution),
            ProvidedTool.TextEditor(AITool_Claude.TextEditor._4),
            ProvidedTool.WebSearch(AITool_Claude.WebSearch),
        )
        private val Opus_4_TOOLS: List<ProvidedTool<AITool_Claude>> = Opus_4_1_TOOLS
        private val Sonnet_4_TOOLS: List<ProvidedTool<AITool_Claude>> = Opus_4_TOOLS

        private val Sonnet_3_7_TOOLS: List<ProvidedTool<AITool_Claude>> = listOf(
            ProvidedTool.Bash(AITool_Claude.Bash),
            ProvidedTool.CodeExecution(AITool_Claude.CodeExecution),
            ProvidedTool.TextEditor(AITool_Claude.TextEditor._3_7),
            ProvidedTool.WebSearch(AITool_Claude.WebSearch),
        )

        private val Haiku_3_5_TOOLS: List<ProvidedTool<AITool_Claude>> = listOf(
            ProvidedTool.CodeExecution(AITool_Claude.CodeExecution),
            ProvidedTool.WebSearch(AITool_Claude.WebSearch),
        )

        private val Haiku_3_TOOLS = emptyList<ProvidedTool<AITool_Claude>>()

        private val Opus_4_5_TOOLS: List<ProvidedTool<AITool_Claude>> = Opus_4_1_TOOLS
        private val Sonnet_4_5_TOOLS: List<ProvidedTool<AITool_Claude>> = Opus_4_TOOLS
        private val Haiku_4_5_TOOLS: List<ProvidedTool<AITool_Claude>> = Haiku_3_5_TOOLS
        private val CURRENT_GENERATION_TOOLS: List<ProvidedTool<AITool_Claude>> = Opus_4_1_TOOLS

        // ---- Rate Limits ----

        private const val TIER_FREE_RPM = 5
        private const val TIER_1_RPM = 50
        private const val TIER_2_RPM = 1000
        private const val TIER_3_RPM = 2000
        private const val TIER_4_RPM = 4000

        private val rateLimitsFactory = RateLimitsFactory(
            tierFreeRequestLimits = Pair(TIER_FREE_RPM, null),
            tier1RequestLimits = Pair(TIER_1_RPM, null),
            tier2RequestLimits = Pair(TIER_2_RPM, null),
            tier3RequestLimits = Pair(TIER_3_RPM, null),
            tier4RequestLimits = Pair(TIER_4_RPM, null),
        )

        private val Opus_4_1_RATE_LIMITS = rateLimitsFactory.createSeparatedRateLimits(
            tierFreeTPMs = TokenCount._10k to TokenCount._4k,
            tier1TPMs = TokenCount._30k to TokenCount._8k,
            tier2TPMs = TokenCount._450k to TokenCount._90k,
            tier3TPMs = TokenCount._800k to TokenCount._160k,
            tier4TPMs = TokenCount._2m to TokenCount._400k,
        )
        private val Opus_4_RATE_LIMITS = Opus_4_1_RATE_LIMITS
        private val Sonnet_4_RATE_LIMITS = Opus_4_RATE_LIMITS

        private val Sonnet_3_7_RATE_LIMITS = rateLimitsFactory.createSeparatedRateLimits(
            tierFreeTPMs = TokenCount._10k to TokenCount._4k,
            tier1TPMs = TokenCount._20k to TokenCount._8k,
            tier2TPMs = TokenCount._40k to TokenCount._16k,
            tier3TPMs = TokenCount._80k to TokenCount._32k,
            tier4TPMs = TokenCount._200k to TokenCount._80k,
        )

        private val Haiku_3_5_RATE_LIMITS = rateLimitsFactory.createSeparatedRateLimits(
            tierFreeTPMs = TokenCount._25k to TokenCount._5k,
            tier1TPMs = TokenCount._50k to TokenCount._10k,
            tier2TPMs = TokenCount._100k to TokenCount._20k,
            tier3TPMs = TokenCount._200k to TokenCount._40k,
            tier4TPMs = TokenCount._400k to TokenCount._80k,
        )
        private val Haiku_3_RATE_LIMITS = Haiku_3_5_RATE_LIMITS

        private val Opus_4_5_RATE_LIMITS = Opus_4_1_RATE_LIMITS
        private val Sonnet_4_5_RATE_LIMITS = Sonnet_4_RATE_LIMITS
        private val Haiku_4_5_RATE_LIMITS = Haiku_3_5_RATE_LIMITS
        private val CURRENT_GENERATION_OPUS_RATE_LIMITS = Opus_4_1_RATE_LIMITS
        private val CURRENT_GENERATION_SONNET_RATE_LIMITS = Sonnet_4_RATE_LIMITS

        // ---- Token Limits ----

        private val CONTEXT_WINDOW_TOKENS = TokenCount._200k

        private val Opus_4_1_TOKEN_LIMITS = TokenLimits(
            contextWindow = CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._32k,
        )
        private val Opus_4_TOKEN_LIMITS = Opus_4_1_TOKEN_LIMITS

        private val Sonnet_4_TOKEN_LIMITS = TokenLimits(
            contextWindow = CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._64k,
        )
        private val Sonnet_3_7_TOKEN_LIMITS = Sonnet_4_TOKEN_LIMITS

        private val Haiku_3_5_TOKEN_LIMITS = TokenLimits(
            contextWindow = CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._8192,
        )

        private val Haiku_3_TOKEN_LIMITS = TokenLimits(
            contextWindow = CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._4096,
        )

        private val Opus_4_5_TOKEN_LIMITS = Opus_4_1_TOKEN_LIMITS
        private val Sonnet_4_5_TOKEN_LIMITS = Sonnet_4_TOKEN_LIMITS
        private val Haiku_4_5_TOKEN_LIMITS = Haiku_3_5_TOKEN_LIMITS

        // 1M context and 128K max output (synchronous Messages API) across the
        // 4.6 generation onward.
        private val CURRENT_GENERATION_TOKEN_LIMITS = TokenLimits(
            contextWindow = TokenCount._1m,
            maxOutput = TokenCount._128k,
        )

        // ---- Limits ----

        private val Opus_4_1_LIMITS = ModelLimits(
            rate = Opus_4_1_RATE_LIMITS,
            token = Opus_4_1_TOKEN_LIMITS,
        )

        private val Opus_4_LIMITS = ModelLimits(
            rate = Opus_4_RATE_LIMITS,
            token = Opus_4_TOKEN_LIMITS,
        )

        private val Sonnet_4_LIMITS = ModelLimits(
            rate = Sonnet_4_RATE_LIMITS,
            token = Sonnet_4_TOKEN_LIMITS,
        )

        private val Sonnet_3_7_LIMITS = ModelLimits(
            rate = Sonnet_3_7_RATE_LIMITS,
            token = Sonnet_3_7_TOKEN_LIMITS,
        )

        private val Haiku_3_5_LIMITS = ModelLimits(
            rate = Haiku_3_5_RATE_LIMITS,
            token = Haiku_3_5_TOKEN_LIMITS,
        )

        private val Haiku_3_LIMITS = ModelLimits(
            rate = Haiku_3_RATE_LIMITS,
            token = Haiku_3_TOKEN_LIMITS,
        )

        private val Opus_4_5_LIMITS = ModelLimits(
            rate = Opus_4_5_RATE_LIMITS,
            token = Opus_4_5_TOKEN_LIMITS,
        )

        private val Sonnet_4_5_LIMITS = ModelLimits(
            rate = Sonnet_4_5_RATE_LIMITS,
            token = Sonnet_4_5_TOKEN_LIMITS,
        )

        private val Haiku_4_5_LIMITS = ModelLimits(
            rate = Haiku_4_5_RATE_LIMITS,
            token = Haiku_4_5_TOKEN_LIMITS,
        )

        private val CURRENT_GENERATION_OPUS_LIMITS = ModelLimits(
            rate = CURRENT_GENERATION_OPUS_RATE_LIMITS,
            token = CURRENT_GENERATION_TOKEN_LIMITS,
        )

        private val CURRENT_GENERATION_SONNET_LIMITS = ModelLimits(
            rate = CURRENT_GENERATION_SONNET_RATE_LIMITS,
            token = CURRENT_GENERATION_TOKEN_LIMITS,
        )

        // ---- Training Cutoffs ----

        private val Opus_4_1_CUTOFF = GMTDate(
            year = 2025,
            month = Month.MARCH,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Opus_4_CUTOFF = Opus_4_1_CUTOFF
        private val Sonnet_4_CUTOFF = Opus_4_CUTOFF

        private val Sonnet_3_7_CUTOFF = GMTDate(
            year = 2024,
            month = Month.NOVEMBER,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Haiku_3_5_CUTOFF = GMTDate(
            year = 2024,
            month = Month.JULY,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Haiku_3_CUTOFF = GMTDate(
            year = 2023,
            month = Month.AUGUST,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Opus_4_5_CUTOFF = GMTDate(
            year = 2025,
            month = Month.NOVEMBER,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Sonnet_4_5_CUTOFF = GMTDate(
            year = 2025,
            month = Month.SEPTEMBER,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Haiku_4_5_CUTOFF = GMTDate(
            year = 2025,
            month = Month.OCTOBER,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        // Reliable knowledge cutoffs, per Anthropic's model pages.

        private val Opus_4_6_CUTOFF = GMTDate(
            year = 2025,
            month = Month.MAY,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Opus_4_7_CUTOFF = GMTDate(
            year = 2026,
            month = Month.JANUARY,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Opus_4_8_CUTOFF = Opus_4_7_CUTOFF

        private val Opus_5_CUTOFF = GMTDate(
            year = 2026,
            month = Month.MAY,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Sonnet_4_6_CUTOFF = GMTDate(
            year = 2025,
            month = Month.AUGUST,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Sonnet_5_CUTOFF = GMTDate(
            year = 2026,
            month = Month.JANUARY,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val Fable_5_1_CUTOFF = GMTDate(
            year = 2026,
            month = Month.JUNE,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        // ---- Supported Inputs ----

        private val Opus_4_1_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val Opus_4_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val Sonnet_4_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val Sonnet_3_7_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val Haiku_3_5_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val Haiku_3_SUPPORTED_INPUTS = TEXT_AND_IMAGE

        private val Opus_4_5_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val Sonnet_4_5_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val Haiku_4_5_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val CURRENT_GENERATION_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF

        // ---- Model Features ----

        private val Opus_4_1_FEATURES = AIModelFeatures(
            availableTools = Opus_4_1_TOOLS,
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            supportedInputs = Opus_4_1_SUPPORTED_INPUTS,
            trainingCutoffDate = Opus_4_1_CUTOFF,
        )

        private val Opus_4_FEATURES = AIModelFeatures(
            availableTools = Opus_4_TOOLS,
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            supportedInputs = Opus_4_SUPPORTED_INPUTS,
            trainingCutoffDate = Opus_4_CUTOFF,
        )

        private val Opus_4_5_FEATURES = AIModelFeatures(
            availableTools = Opus_4_5_TOOLS,
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            supportedInputs = Opus_4_5_SUPPORTED_INPUTS,
            trainingCutoffDate = Opus_4_5_CUTOFF,
        )

        private fun featuresForSonnet(
            availableTools: List<ProvidedTool<AITool_Claude>>,
            supportedInputs: SupportedInputs,
            cutoffDate: GMTDate,
        ): AIModelFeatures = AIModelFeatures(
            availableTools = availableTools,
            reasoningLevel = RelativeReasoning.NORMAL,
            speed = RelativeSpeed.NORMAL,
            supportedInputs = supportedInputs,
            trainingCutoffDate = cutoffDate,
        )

        private fun featuresForHaiku(
            availableTools: List<ProvidedTool<AITool_Claude>>,
            supportedInputs: SupportedInputs,
            cutoffDate: GMTDate,
        ): AIModelFeatures = AIModelFeatures(
            availableTools = availableTools,
            reasoningLevel = RelativeReasoning.NORMAL,
            speed = RelativeSpeed.FAST,
            supportedInputs = supportedInputs,
            trainingCutoffDate = cutoffDate,
        )

        /**
         * Features for the 4.6 generation onward. [supportsSamplingParameters]
         * is `false` from Opus 4.7 on: those models reject `temperature` and
         * `top_p` with a 400, including through the OpenAI-compatible endpoint.
         */
        private fun currentGenerationFeatures(
            reasoningLevel: RelativeReasoning,
            speed: RelativeSpeed,
            cutoffDate: GMTDate,
            supportsSamplingParameters: Boolean,
        ): AIModelFeatures = AIModelFeatures(
            availableTools = CURRENT_GENERATION_TOOLS,
            reasoningLevel = reasoningLevel,
            speed = speed,
            supportedInputs = CURRENT_GENERATION_SUPPORTED_INPUTS,
            trainingCutoffDate = cutoffDate,
            supportsSamplingParameters = supportsSamplingParameters,
        )

        // Haiku 3 is older and weaker, so it gets LOW reasoning
        private val Haiku_3_FEATURES = AIModelFeatures(
            availableTools = Haiku_3_TOOLS,
            reasoningLevel = RelativeReasoning.LOW,
            speed = RelativeSpeed.FAST,
            supportedInputs = Haiku_3_SUPPORTED_INPUTS,
            trainingCutoffDate = Haiku_3_CUTOFF,
        )

        // ---- Model Names ----

        private const val Opus_4_1_NAME = "claude-opus-4-1"
        private const val Opus_4_1_DISPLAY_NAME = "Claude Opus 4.1"
        private const val Opus_4_1_DESCRIPTION = "Our most capable model. Highest level of intelligence and capability."

        private const val Opus_4_NAME = "claude-opus-4-0"
        private const val Opus_4_DISPLAY_NAME = "Claude Opus 4"
        private const val Opus_4_DESCRIPTION = "Our previous flagship model. Very high intelligence and capability."

        private const val Sonnet_4_NAME = "claude-sonnet-4-0"
        private const val Sonnet_4_DISPLAY_NAME = "Claude Sonnet 4"
        private const val Sonnet_4_DESCRIPTION = "High-performance model. High intelligence and balanced performance."

        private const val Sonnet_3_7_NAME = "claude-3-7-sonnet-latest"
        private const val Sonnet_3_7_DISPLAY_NAME = "Claude Sonnet 3.7"
        private const val Sonnet_3_7_DESCRIPTION =
            "High-performance model with early extended thinking. " +
                "High intelligence with toggleable extended thinking."

        private const val Haiku_3_5_NAME = "claude-3-5-haiku-latest"
        private const val Haiku_3_5_DISPLAY_NAME = "Claude Haiku 3.5"
        private const val Haiku_3_5_DESCRIPTION = "Our fastest model. Intelligence at blazing speeds."

        private const val Haiku_3_NAME = "claude-3-haiku-20240307"
        private const val Haiku_3_DISPLAY_NAME = "Claude Haiku 3"
        private const val Haiku_3_DESCRIPTION =
            "Fast and compact model for near-instant responsiveness. " +
                "Quick and accurate targeted performance."

        private const val Opus_4_5_NAME = "claude-opus-4-5-20251101"
        private const val Opus_4_5_DISPLAY_NAME = "Claude Opus 4.5"
        private const val Opus_4_5_DESCRIPTION =
            "Our newest flagship model setting new standards across coding, " +
                "agents, computer use, and office tasks. Supports an effort " +
                "parameter for trading compute/tokens for reasoning depth."

        private const val Sonnet_4_5_NAME = "claude-sonnet-4-5-20250929"
        private const val Sonnet_4_5_DISPLAY_NAME = "Claude Sonnet 4.5"
        private const val Sonnet_4_5_DESCRIPTION =
            "The best coding model in the world. Leads OSWorld at 61.4% and " +
                "maintains concentration for 30+ hours on complex tasks. Features a 1M token context window."

        private const val Haiku_4_5_NAME = "claude-haiku-4-5"
        private const val Haiku_4_5_DISPLAY_NAME = "Claude Haiku 4.5"
        private const val Haiku_4_5_DESCRIPTION =
            "Near-frontier coding power at a fraction of the cost. Matches " +
                "Claude Sonnet 4's performance on coding tasks while being faster and more affordable."

        private const val Opus_4_6_NAME = "claude-opus-4-6"
        private const val Opus_4_6_DISPLAY_NAME = "Claude Opus 4.6"
        private const val Opus_4_6_DESCRIPTION =
            "Previous-generation Opus with adaptive thinking and a 1M token context window."

        private const val Opus_4_7_NAME = "claude-opus-4-7"
        private const val Opus_4_7_DISPLAY_NAME = "Claude Opus 4.7"
        private const val Opus_4_7_DESCRIPTION =
            "Highly autonomous Opus for long-horizon agentic work, knowledge work, and vision. " +
                "Adaptive thinking only; rejects sampling parameters."

        private const val Opus_4_8_NAME = "claude-opus-4-8"
        private const val Opus_4_8_DISPLAY_NAME = "Claude Opus 4.8"
        private const val Opus_4_8_DESCRIPTION =
            "The most capable model in the Opus 4 series. State-of-the-art on long-horizon " +
                "agentic work, knowledge work, and memory. Adaptive thinking only; rejects sampling parameters."

        private const val Opus_5_NAME = "claude-opus-5"
        private const val Opus_5_DISPLAY_NAME = "Claude Opus 5"
        private const val Opus_5_DESCRIPTION =
            "For complex agentic coding and enterprise work. Strongest on deep reasoning and " +
                "long-horizon tasks. Adaptive thinking on by default; rejects sampling parameters."

        private const val Sonnet_4_6_NAME = "claude-sonnet-4-6"
        private const val Sonnet_4_6_DISPLAY_NAME = "Claude Sonnet 4.6"
        private const val Sonnet_4_6_DESCRIPTION =
            "Previous-generation Sonnet with adaptive thinking and a 1M token context window."

        private const val Sonnet_5_NAME = "claude-sonnet-5"
        private const val Sonnet_5_DISPLAY_NAME = "Claude Sonnet 5"
        private const val Sonnet_5_DESCRIPTION =
            "The best combination of speed and intelligence. Near-Opus quality on coding and " +
                "agentic work. Adaptive thinking on by default; rejects sampling parameters."

        private const val Fable_5_1_NAME = "claude-fable-5-1"
        private const val Fable_5_1_DISPLAY_NAME = "Claude Fable 5.1"
        private const val Fable_5_1_DESCRIPTION =
            "Anthropic's most capable widely released model, for demanding reasoning and " +
                "long-horizon agentic work. Thinking is always on; rejects sampling parameters."

        // ---- Models ----

        // Lazy to avoid the JVM class-init cycle: if any data object (Opus_4_1, etc.)
        // is accessed before this companion finishes initializing, the companion's
        // <clinit> would see that object's INSTANCE as null and commit a list with
        // null entries. Lazy defers evaluation until the companion is fully set up.
        val ALL_MODELS by lazy {
            listOf(
                Opus_4_1,
                Opus_4,
                Opus_4_5,
                Sonnet_4,
                Sonnet_3_7,
                Sonnet_4_5,
                Haiku_3_5,
                Haiku_3,
                Haiku_4_5,
                Opus_4_6,
                Opus_4_7,
                Opus_4_8,
                Opus_5,
                Sonnet_4_6,
                Sonnet_5,
                Fable_5_1,
            )
        }
    }
}
