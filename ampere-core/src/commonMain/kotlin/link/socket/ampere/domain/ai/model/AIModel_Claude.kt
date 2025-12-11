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
        features = featuresForHaiku(
            availableTools = Haiku_3_TOOLS,
            supportedInputs = Haiku_3_SUPPORTED_INPUTS,
            cutoffDate = Haiku_3_CUTOFF,
        ),
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
            reasoningLevel = RelativeReasoning.HIGH,
            speed = RelativeSpeed.FAST,
            supportedInputs = supportedInputs,
            trainingCutoffDate = cutoffDate,
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
        private const val Sonnet_3_7_DESCRIPTION = "High-performance model with early extended thinking. High intelligence with toggleable extended thinking."

        private const val Haiku_3_5_NAME = "claude-3-5-haiku-latest"
        private const val Haiku_3_5_DISPLAY_NAME = "Claude Haiku 3.5"
        private const val Haiku_3_5_DESCRIPTION = "Our fastest model. Intelligence at blazing speeds."

        private const val Haiku_3_NAME = "claude-3-haiku-20240307"
        private const val Haiku_3_DISPLAY_NAME = "Claude Haiku 3"
        private const val Haiku_3_DESCRIPTION = "Fast and compact model for near-instant responsiveness. Quick and accurate targeted performance."

        private const val Opus_4_5_NAME = "claude-opus-4-5-20251101"
        private const val Opus_4_5_DISPLAY_NAME = "Claude Opus 4.5"
        private const val Opus_4_5_DESCRIPTION = "Our newest flagship model setting new standards across coding, agents, computer use, and office tasks. Supports an effort parameter for trading compute/tokens for reasoning depth."

        private const val Sonnet_4_5_NAME = "claude-sonnet-4-5-20250929"
        private const val Sonnet_4_5_DISPLAY_NAME = "Claude Sonnet 4.5"
        private const val Sonnet_4_5_DESCRIPTION = "The best coding model in the world. Leads OSWorld at 61.4% and maintains concentration for 30+ hours on complex tasks. Features a 1M token context window."

        private const val Haiku_4_5_NAME = "claude-haiku-4-5"
        private const val Haiku_4_5_DISPLAY_NAME = "Claude Haiku 4.5"
        private const val Haiku_4_5_DESCRIPTION = "Near-frontier coding power at a fraction of the cost. Matches Claude Sonnet 4's performance on coding tasks while being faster and more affordable."

        // ---- Models ----

        val ALL_MODELS = listOf(
            Opus_4_1,
            Opus_4,
            Opus_4_5,
            Sonnet_4,
            Sonnet_3_7,
            Sonnet_4_5,
            Haiku_3_5,
            Haiku_3,
            Haiku_4_5,
        )
    }
}
