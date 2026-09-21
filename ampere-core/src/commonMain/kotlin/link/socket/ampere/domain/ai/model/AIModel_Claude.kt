@file:Suppress("ClassName", "ObjectPropertyName", "ObjectPrivatePropertyName")

package link.socket.ampere.domain.ai.model

import io.ktor.util.date.GMTDate
import io.ktor.util.date.Month
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeSpeed
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs.Companion.TEXT_IMAGE_AND_PDF
import link.socket.ampere.domain.limits.ModelLimits
import link.socket.ampere.domain.limits.RateLimitsFactory
import link.socket.ampere.domain.limits.TokenCount
import link.socket.ampere.domain.limits.TokenLimits
import link.socket.ampere.domain.tool.AITool_Claude
import link.socket.ampere.domain.tool.ProvidedTool

/**
 * The Claude models Ampere ships an entry for.
 *
 * Only models Anthropic still serves are listed. Retired IDs are removed rather
 * than kept as documentation — a model in this catalog is a model the relay may
 * route to, and routing to a retired ID can only fail (AMPR-326). Use [Custom]
 * to address a model that has no entry here.
 */
sealed class AIModel_Claude(
    override val name: String,
    override val displayName: String,
    override val description: String,
    override val features: AIModelFeatures,
    override val limits: ModelLimits,
) : AIModel(name, displayName, description, features, limits) {

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
        limits = CURRENT_GENERATION_LIMITS,
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
        limits = CURRENT_GENERATION_LIMITS,
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
        limits = CURRENT_GENERATION_LIMITS,
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
        limits = CURRENT_GENERATION_LIMITS,
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
        limits = CURRENT_GENERATION_LIMITS,
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
        limits = CURRENT_GENERATION_LIMITS,
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
        limits = FABLE_LIMITS,
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

        private val STANDARD_TOOLS: List<ProvidedTool<AITool_Claude>> = listOf(
            ProvidedTool.Bash(AITool_Claude.Bash),
            ProvidedTool.CodeExecution(AITool_Claude.CodeExecution),
            ProvidedTool.TextEditor(AITool_Claude.TextEditor._4),
            ProvidedTool.WebSearch(AITool_Claude.WebSearch),
        )

        private val HAIKU_TOOLS: List<ProvidedTool<AITool_Claude>> = listOf(
            ProvidedTool.CodeExecution(AITool_Claude.CodeExecution),
            ProvidedTool.WebSearch(AITool_Claude.WebSearch),
        )

        private val Opus_4_5_TOOLS: List<ProvidedTool<AITool_Claude>> = STANDARD_TOOLS
        private val Sonnet_4_5_TOOLS: List<ProvidedTool<AITool_Claude>> = STANDARD_TOOLS
        private val Haiku_4_5_TOOLS: List<ProvidedTool<AITool_Claude>> = HAIKU_TOOLS
        private val CURRENT_GENERATION_TOOLS: List<ProvidedTool<AITool_Claude>> = STANDARD_TOOLS

        // ---- Rate Limits ----

        // Anthropic's published tiers are Start / Build / Scale / Custom — there
        // is no free tier on the API, and Custom publishes no numbers. They map
        // onto tiers 1-3 here; tier 4+ is left unset rather than invented.
        private const val TIER_START_RPM = 1000
        private const val TIER_BUILD_RPM = 5000
        private const val TIER_SCALE_RPM = 10000
        private const val TIER_FABLE_BUILD_RPM = 2000
        private const val TIER_FABLE_SCALE_RPM = 4000

        private val standardRateLimitsFactory = RateLimitsFactory(
            tier1RequestLimits = Pair(TIER_START_RPM, null),
            tier2RequestLimits = Pair(TIER_BUILD_RPM, null),
            tier3RequestLimits = Pair(TIER_SCALE_RPM, null),
        )

        /**
         * One published row covers Opus 5, Sonnet 5, Haiku 4.5 and the Opus 4.x
         * / Sonnet 4.x pools. Limits are separate ITPM/OTPM, and for most models
         * cached input reads don't count toward ITPM.
         *
         * These are per-*pool*, not per-model: traffic across Opus 4.8/4.7/4.6/
         * 4.5 counts against one bucket, as does Sonnet 4.6/4.5. Opus 5 and
         * Sonnet 5 each have their own.
         */
        private val STANDARD_RATE_LIMITS = standardRateLimitsFactory.createSeparatedRateLimits(
            tier1TPMs = TokenCount._2m to TokenCount._400k,
            tier2TPMs = TokenCount._5m to TokenCount._1m,
            tier3TPMs = TokenCount._10m to TokenCount._2m,
        )

        /** Fable 5.x sits well below the rest, and pools across 5.1 and 5. */
        private val FABLE_RATE_LIMITS = RateLimitsFactory(
            tier1RequestLimits = Pair(TIER_START_RPM, null),
            tier2RequestLimits = Pair(TIER_FABLE_BUILD_RPM, null),
            tier3RequestLimits = Pair(TIER_FABLE_SCALE_RPM, null),
        ).createSeparatedRateLimits(
            tier1TPMs = TokenCount._500k to TokenCount._100k,
            tier2TPMs = TokenCount._1_5m to TokenCount._300k,
            tier3TPMs = TokenCount._4m to TokenCount._800k,
        )

        // ---- Token Limits ----

        private val LEGACY_CONTEXT_WINDOW_TOKENS = TokenCount._200k

        private val Opus_4_5_TOKEN_LIMITS = TokenLimits(
            contextWindow = LEGACY_CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._32k,
        )

        private val Sonnet_4_5_TOKEN_LIMITS = TokenLimits(
            contextWindow = LEGACY_CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._64k,
        )

        private val Haiku_4_5_TOKEN_LIMITS = TokenLimits(
            contextWindow = LEGACY_CONTEXT_WINDOW_TOKENS,
            maxOutput = TokenCount._64k,
        )

        // 1M context and 128K max output (synchronous Messages API) across the
        // 4.6 generation onward.
        private val CURRENT_GENERATION_TOKEN_LIMITS = TokenLimits(
            contextWindow = TokenCount._1m,
            maxOutput = TokenCount._128k,
        )

        // ---- Limits ----

        private val Opus_4_5_LIMITS = ModelLimits(
            rate = STANDARD_RATE_LIMITS,
            token = Opus_4_5_TOKEN_LIMITS,
        )

        private val Sonnet_4_5_LIMITS = ModelLimits(
            rate = STANDARD_RATE_LIMITS,
            token = Sonnet_4_5_TOKEN_LIMITS,
        )

        private val Haiku_4_5_LIMITS = ModelLimits(
            rate = STANDARD_RATE_LIMITS,
            token = Haiku_4_5_TOKEN_LIMITS,
        )

        private val CURRENT_GENERATION_LIMITS = ModelLimits(
            rate = STANDARD_RATE_LIMITS,
            token = CURRENT_GENERATION_TOKEN_LIMITS,
        )

        private val FABLE_LIMITS = ModelLimits(
            rate = FABLE_RATE_LIMITS,
            token = CURRENT_GENERATION_TOKEN_LIMITS,
        )

        // ---- Training Cutoffs ----

        // Reliable knowledge cutoffs, per Anthropic's model pages.

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
            month = Month.FEBRUARY,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

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

        private val Opus_4_5_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val Sonnet_4_5_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val Haiku_4_5_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF
        private val CURRENT_GENERATION_SUPPORTED_INPUTS = TEXT_IMAGE_AND_PDF

        // ---- Model Features ----

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

        // ---- Model Names ----

        private const val Opus_4_5_NAME = "claude-opus-4-5-20251101"
        private const val Opus_4_5_DISPLAY_NAME = "Claude Opus 4.5"
        private const val Opus_4_5_DESCRIPTION =
            "Legacy Opus, strong across coding, agents, computer use, and " +
                "office tasks. Supports an effort parameter for trading " +
                "compute/tokens for reasoning depth."

        private const val Sonnet_4_5_NAME = "claude-sonnet-4-5-20250929"
        private const val Sonnet_4_5_DISPLAY_NAME = "Claude Sonnet 4.5"
        private const val Sonnet_4_5_DESCRIPTION =
            "Legacy Sonnet, a strong coding model that maintains concentration " +
                "for 30+ hours on complex tasks."

        private const val Haiku_4_5_NAME = "claude-haiku-4-5"
        private const val Haiku_4_5_DISPLAY_NAME = "Claude Haiku 4.5"
        private const val Haiku_4_5_DESCRIPTION =
            "The fastest model, with near-frontier intelligence at a fraction " +
                "of the cost. Takes manual extended thinking (`budget_tokens`) " +
                "rather than adaptive thinking."

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

        // Lazy to avoid the JVM class-init cycle: if any data object (Opus_5, etc.)
        // is accessed before this companion finishes initializing, the companion's
        // <clinit> would see that object's INSTANCE as null and commit a list with
        // null entries. Lazy defers evaluation until the companion is fully set up.
        val ALL_MODELS by lazy {
            listOf(
                Opus_4_5,
                Sonnet_4_5,
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
