@file:Suppress("ClassName", "ObjectPropertyName", "ObjectPrivatePropertyName", "ConstPropertyName")

package link.socket.ampere.domain.ai.model

import io.ktor.util.date.GMTDate
import io.ktor.util.date.Month
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs.Companion.TEXT
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs.Companion.TEXT_AND_IMAGE
import link.socket.ampere.domain.limits.ModelLimits
import link.socket.ampere.domain.limits.RateLimitsFactory
import link.socket.ampere.domain.limits.TokenCount
import link.socket.ampere.domain.limits.TokenLimits
import link.socket.ampere.domain.tool.AITool_OpenAI
import link.socket.ampere.domain.tool.ProvidedTool

sealed class AIModel_OpenAI(
    override val name: String,
    override val displayName: String,
    override val description: String,
    override val features: AIModelFeatures,
    override val limits: ModelLimits,
) : AIModel(name, displayName, description, features, limits) {

    data object GPT_5 : AIModel_OpenAI(
        name = GPT_5_NAME,
        displayName = GPT_5_DISPLAY_NAME,
        description = GPT_5_DESCRIPTION,
        features = GPT_5_FEATURES,
        limits = GPT_5_LIMITS,
    )

    data object GPT_5_mini : AIModel_OpenAI(
        name = GPT_5_mini_NAME,
        displayName = GPT_5_mini_DISPLAY_NAME,
        description = GPT_5_mini_DESCRIPTION,
        features = GPT_5_mini_FEATURES,
        limits = GPT_5_mini_LIMITS,
    )

    data object GPT_5_nano : AIModel_OpenAI(
        name = GPT_5_nano_NAME,
        displayName = GPT_5_nano_DISPLAY_NAME,
        description = GPT_5_nano_DESCRIPTION,
        features = GPT_5_nano_FEATURES,
        limits = GPT_5_nano_LIMITS,
    )

    data object GPT_4_1 : AIModel_OpenAI(
        name = GPT_4_1_NAME,
        displayName = GPT_4_1_DISPLAY_NAME,
        description = GPT_4_1_DESCRIPTION,
        features = GPT_4_1_FEATURES,
        limits = GPT_4_1_LIMITS,
    )

    data object GPT_4_1_mini : AIModel_OpenAI(
        name = GPT_4_1_mini_NAME,
        displayName = GPT_4_1_mini_DISPLAY_NAME,
        description = GPT_4_1_mini_DESCRIPTION,
        features = GPT_4_1_mini_FEATURES,
        limits = GPT_4_1_mini_LIMITS,
    )

    data object GPT_4o : AIModel_OpenAI(
        name = GPT_4o_NAME,
        displayName = GPT_4o_DISPLAY_NAME,
        description = GPT_4o_DESCRIPTION,
        features = GPT_4o_FEATURES,
        limits = GPT_4o_LIMITS,
    )

    data object GPT_4o_mini : AIModel_OpenAI(
        name = GPT_4o_mini_NAME,
        displayName = GPT_4o_mini_DISPLAY_NAME,
        description = GPT_4o_mini_DESCRIPTION,
        features = GPT_4o_mini_FEATURES,
        limits = GPT_4o_mini_LIMITS,
    )

    data object o4_mini : AIModel_OpenAI(
        name = o4_mini_NAME,
        displayName = o4_mini_DISPLAY_NAME,
        description = o4_mini_DESCRIPTION,
        features = o4_mini_FEATURES,
        limits = o4_mini_LIMITS,
    )

    data object o3 : AIModel_OpenAI(
        name = o3_NAME,
        displayName = o3_DISPLAY_NAME,
        description = o3_DESCRIPTION,
        features = o3_FEATURES,
        limits = o3_LIMITS,
    )

    data object o3_mini : AIModel_OpenAI(
        name = o3_mini_NAME,
        displayName = o3_mini_DISPLAY_NAME,
        description = o3_mini_DESCRIPTION,
        features = o3_mini_FEATURES,
        limits = o3_mini_LIMITS,
    )

    data object GPT_5_1 : AIModel_OpenAI(
        name = GPT_5_1_NAME,
        displayName = GPT_5_1_DISPLAY_NAME,
        description = GPT_5_1_DESCRIPTION,
        features = GPT_5_1_FEATURES,
        limits = GPT_5_1_LIMITS,
    )

    data object GPT_5_4 : AIModel_OpenAI(
        name = GPT_5_4_NAME,
        displayName = GPT_5_4_DISPLAY_NAME,
        description = GPT_5_4_DESCRIPTION,
        features = GPT_5_4_FEATURES,
        limits = GPT_5_4_LIMITS,
    )

    data object GPT_5_4_mini : AIModel_OpenAI(
        name = GPT_5_4_mini_NAME,
        displayName = GPT_5_4_mini_DISPLAY_NAME,
        description = GPT_5_4_mini_DESCRIPTION,
        features = GPT_5_4_mini_FEATURES,
        limits = GPT_5_4_mini_LIMITS,
    )

    /**
     * Defaults to reasoning effort `medium`. From GPT-5.4 on, Chat Completions
     * rejects tool calls at any effort other than `none`, and Ampere sends no
     * effort — so use [GPT_5_4] for tool-calling agents.
     */
    data object GPT_5_5 : AIModel_OpenAI(
        name = GPT_5_5_NAME,
        displayName = GPT_5_5_DISPLAY_NAME,
        description = GPT_5_5_DESCRIPTION,
        features = GPT_5_5_FEATURES,
        limits = GPT_5_5_LIMITS,
    )

    /** Same Chat Completions tool-calling caveat as [GPT_5_5]. */
    data object GPT_5_6_Sol : AIModel_OpenAI(
        name = GPT_5_6_Sol_NAME,
        displayName = GPT_5_6_Sol_DISPLAY_NAME,
        description = GPT_5_6_Sol_DESCRIPTION,
        features = GPT_5_6_Sol_FEATURES,
        limits = GPT_5_6_Sol_LIMITS,
    )

    /**
     * An OpenAI model Ampere has no bundled entry for, constructed by ID so a
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
    ) : AIModel_OpenAI(name, displayName, description, features, limits)

    companion object Companion {

        // ---- Tools ----

        private val GPT_5_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf(
            ProvidedTool.CodeExecution(AITool_OpenAI.CodeExecution),
            ProvidedTool.FileSearch(AITool_OpenAI.FileSearch),
            ProvidedTool.ImageGeneration(AITool_OpenAI.ImageGeneration),
            ProvidedTool.MCP(AITool_OpenAI.MCP),
            ProvidedTool.WebSearch(AITool_OpenAI.WebSearch),
        )

        private val GPT_5_mini_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf(
            ProvidedTool.CodeExecution(AITool_OpenAI.CodeExecution),
            ProvidedTool.FileSearch(AITool_OpenAI.FileSearch),
            ProvidedTool.MCP(AITool_OpenAI.MCP),
            ProvidedTool.WebSearch(AITool_OpenAI.WebSearch),
        )

        private val GPT_5_nano_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf(
            ProvidedTool.CodeExecution(AITool_OpenAI.CodeExecution),
            ProvidedTool.FileSearch(AITool_OpenAI.FileSearch),
            ProvidedTool.ImageGeneration(AITool_OpenAI.ImageGeneration),
            ProvidedTool.MCP(AITool_OpenAI.MCP),
        )

        private val GPT_4_1_TOOLS: List<ProvidedTool<AITool_OpenAI>> = GPT_5_TOOLS

        // TODO: Verify
        private val GPT_4_1_mini_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf()
        private val GPT_4o_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf()
        private val GPT_4o_mini_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf()
        private val o4_mini_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf()
        private val o3_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf()
        private val o3_mini_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf()

        private val GPT_5_1_TOOLS: List<ProvidedTool<AITool_OpenAI>> = GPT_5_TOOLS

        // ---- Rate Limits ----

        // TODO: Verify
        private const val TIER_FREE_RPM = 500
        private const val TIER_1_RPM = 1000
        private const val TIER_2_RPM = 5000
        private const val TIER_3_RPM = 10000
        private const val TIER_4_RPM = 50000

        private val nonFreeRateLimitsFactory = RateLimitsFactory(
            tier1RequestLimits = Pair(TIER_1_RPM, null),
            tier2RequestLimits = Pair(TIER_2_RPM, null),
            tier3RequestLimits = Pair(TIER_3_RPM, null),
            tier4RequestLimits = Pair(TIER_4_RPM, null),
        )

        private val freeRateLimitsFactory = RateLimitsFactory(
            tierFreeRequestLimits = Pair(TIER_FREE_RPM, null),
            tier1RequestLimits = Pair(TIER_1_RPM, null),
            tier2RequestLimits = Pair(TIER_2_RPM, null),
            tier3RequestLimits = Pair(TIER_3_RPM, null),
            tier4RequestLimits = Pair(TIER_4_RPM, null),
        )

        private val GPT_5_RATE_LIMITS = nonFreeRateLimitsFactory.createRateLimits(
            tier1TPM = TokenCount._30k,
            tier2TPM = TokenCount._450k,
            tier3TPM = TokenCount._800k,
            tier4TPM = TokenCount._2m,
            tier5TPM = TokenCount._40m,
        )

        private val GPT_5_mini_nano_RATE_LIMITS = nonFreeRateLimitsFactory.createRateLimits(
            tier1TPM = TokenCount._200k,
            tier2TPM = TokenCount._2m,
            tier3TPM = TokenCount._4m,
            tier4TPM = TokenCount._10m,
            tier5TPM = TokenCount._180m,
        )

        private val GPT_4_1_RATE_LIMITS = nonFreeRateLimitsFactory.createRateLimits(
            tier1TPM = TokenCount._30k,
            tier2TPM = TokenCount._450k,
            tier3TPM = TokenCount._800k,
            tier4TPM = TokenCount._2m,
            tier5TPM = TokenCount._30m,
        )

        private val GPT_4_1_mini_RATE_LIMITS = freeRateLimitsFactory.createRateLimits(
            tierFreeTPM = TokenCount._40k,
            tier1TPM = TokenCount._200k,
            tier2TPM = TokenCount._2m,
            tier3TPM = TokenCount._4m,
            tier4TPM = TokenCount._10m,
            tier5TPM = TokenCount._150m,
        )

        private val GPT_4o_RATE_LIMITS = nonFreeRateLimitsFactory.createRateLimits(
            tier1TPM = TokenCount._30k,
            tier2TPM = TokenCount._450k,
            tier3TPM = TokenCount._800k,
            tier4TPM = TokenCount._2m,
            tier5TPM = TokenCount._30m,
        )

        private val GPT_4o_mini_RATE_LIMITS = freeRateLimitsFactory.createRateLimits(
            tierFreeTPM = TokenCount._40k,
            tier1TPM = TokenCount._200k,
            tier2TPM = TokenCount._2m,
            tier3TPM = TokenCount._4m,
            tier4TPM = TokenCount._10m,
            tier5TPM = TokenCount._150m,
        )

        private val o4_mini_RATE_LIMITS = nonFreeRateLimitsFactory.createRateLimits(
            tier1TPM = TokenCount._100k,
            tier2TPM = TokenCount._2m,
            tier3TPM = TokenCount._4m,
            tier4TPM = TokenCount._10m,
            tier5TPM = TokenCount._150m,
        )

        private val o3_RATE_LIMITS = o4_mini_RATE_LIMITS

        private val o3_mini_RATE_LIMITS = nonFreeRateLimitsFactory.createRateLimits(
            tier1TPM = TokenCount._100k,
            tier2TPM = TokenCount._200k,
            tier3TPM = TokenCount._4m,
            tier4TPM = TokenCount._10m,
            tier5TPM = TokenCount._150m,
        )

        private val GPT_5_1_RATE_LIMITS = GPT_5_RATE_LIMITS

        // Published per-model, tiers 1-5; OpenAI lists no free tier for these.
        private const val TIER_1_5_4_RPM = 500
        private const val TIER_2_5_4_RPM = 5000
        private const val TIER_3_5_4_RPM = 5000
        private const val TIER_4_5_4_RPM = 10000
        private const val TIER_5_5_4_RPM = 15000
        private const val TIER_5_5_4_mini_RPM = 30000

        private val currentGenerationRateLimitsFactory = RateLimitsFactory(
            tier1RequestLimits = Pair(TIER_1_5_4_RPM, null),
            tier2RequestLimits = Pair(TIER_2_5_4_RPM, null),
            tier3RequestLimits = Pair(TIER_3_5_4_RPM, null),
            tier4RequestLimits = Pair(TIER_4_5_4_RPM, null),
            tier5RequestLimits = Pair(TIER_5_5_4_RPM, null),
        )

        // One published table covers gpt-5.4, gpt-5.5 and gpt-5.6-sol. (5.4 and
        // 5.5 also publish a separate, lower Long Context table for requests
        // over 272K input tokens, which this model doesn't represent.)
        private val GPT_5_4_RATE_LIMITS = currentGenerationRateLimitsFactory.createRateLimits(
            tier1TPM = TokenCount._500k,
            tier2TPM = TokenCount._1m,
            tier3TPM = TokenCount._2m,
            tier4TPM = TokenCount._4m,
            tier5TPM = TokenCount._40m,
        )

        private val GPT_5_4_mini_RATE_LIMITS = RateLimitsFactory(
            tier1RequestLimits = Pair(TIER_1_5_4_RPM, null),
            tier2RequestLimits = Pair(TIER_2_5_4_RPM, null),
            tier3RequestLimits = Pair(TIER_3_5_4_RPM, null),
            tier4RequestLimits = Pair(TIER_4_5_4_RPM, null),
            tier5RequestLimits = Pair(TIER_5_5_4_mini_RPM, null),
        ).createRateLimits(
            tier1TPM = TokenCount._500k,
            tier2TPM = TokenCount._2m,
            tier3TPM = TokenCount._4m,
            tier4TPM = TokenCount._10m,
            tier5TPM = TokenCount._180m,
        )

        private val GPT_5_5_RATE_LIMITS = GPT_5_4_RATE_LIMITS
        private val GPT_5_6_Sol_RATE_LIMITS = GPT_5_4_RATE_LIMITS

        // ---- Token Limits ----

        private val GPT_5_CONTEXT_WINDOW_TOKENS = TokenCount._400k
        private val GPT_5_MAX_OUTPUT_TOKENS = TokenCount._128k

        private val GPT_4_1_CONTEXT_WINDOW_TOKENS = TokenCount._1m
        private val GPT_4_1_MAX_OUTPUT_TOKENS = TokenCount._32k

        private val GPT_4o_CONTEXT_WINDOW_TOKENS = TokenCount._128k
        private val GPT_4o_MAX_OUTPUT_TOKENS = TokenCount._16k

        private val o_CONTEXT_WINDOW_TOKENS = TokenCount._200k
        private val o_MAX_OUTPUT_TOKENS = TokenCount._100k

        private val GPT_5_TOKEN_LIMITS = TokenLimits(
            contextWindow = GPT_5_CONTEXT_WINDOW_TOKENS,
            maxOutput = GPT_5_MAX_OUTPUT_TOKENS,
        )

        private val GPT_4_1_TOKEN_LIMITS = TokenLimits(
            contextWindow = GPT_4_1_CONTEXT_WINDOW_TOKENS,
            maxOutput = GPT_4_1_MAX_OUTPUT_TOKENS,
        )

        private val GPT_4o_TOKEN_LIMITS = TokenLimits(
            contextWindow = GPT_4o_CONTEXT_WINDOW_TOKENS,
            maxOutput = GPT_4o_MAX_OUTPUT_TOKENS,
        )

        private val GPT_4o_mini_TOKEN_LIMITS = GPT_4o_TOKEN_LIMITS

        private val o4_mini_TOKEN_LIMITS = TokenLimits(
            contextWindow = o_CONTEXT_WINDOW_TOKENS,
            maxOutput = o_MAX_OUTPUT_TOKENS,
        )

        private val o3_TOKEN_LIMITS = o4_mini_TOKEN_LIMITS
        private val o3_mini_TOKEN_LIMITS = o3_TOKEN_LIMITS

        private val GPT_5_1_TOKEN_LIMITS = GPT_5_TOKEN_LIMITS

        // OpenAI lists 1,050,000 tokens; TokenCount's nearest step is 1M.
        private val GPT_5_4_TOKEN_LIMITS = TokenLimits(
            contextWindow = TokenCount._1m,
            maxOutput = GPT_5_MAX_OUTPUT_TOKENS,
        )
        private val GPT_5_4_mini_TOKEN_LIMITS = GPT_5_TOKEN_LIMITS
        private val GPT_5_5_TOKEN_LIMITS = GPT_5_4_TOKEN_LIMITS
        private val GPT_5_6_Sol_TOKEN_LIMITS = GPT_5_4_TOKEN_LIMITS

        // ---- Limits ----

        private val GPT_5_LIMITS = ModelLimits(
            rate = GPT_5_RATE_LIMITS,
            token = GPT_5_TOKEN_LIMITS,
        )

        private val GPT_5_mini_LIMITS = ModelLimits(
            rate = GPT_5_mini_nano_RATE_LIMITS,
            token = GPT_5_TOKEN_LIMITS,
        )

        private val GPT_5_nano_LIMITS = GPT_5_mini_LIMITS

        private val GPT_4_1_LIMITS = ModelLimits(
            rate = GPT_4_1_RATE_LIMITS,
            token = GPT_4_1_TOKEN_LIMITS,
        )

        private val GPT_4_1_mini_LIMITS = ModelLimits(
            rate = GPT_4_1_mini_RATE_LIMITS,
            token = GPT_4_1_TOKEN_LIMITS,
        )

        private val GPT_4o_LIMITS = ModelLimits(
            rate = GPT_4o_RATE_LIMITS,
            token = GPT_4o_TOKEN_LIMITS,
        )

        private val GPT_4o_mini_LIMITS = ModelLimits(
            rate = GPT_4o_mini_RATE_LIMITS,
            token = GPT_4o_mini_TOKEN_LIMITS,
        )

        private val o4_mini_LIMITS = ModelLimits(
            rate = o4_mini_RATE_LIMITS,
            token = o4_mini_TOKEN_LIMITS,
        )

        private val o3_LIMITS = ModelLimits(
            rate = o3_RATE_LIMITS,
            token = o3_TOKEN_LIMITS,
        )

        private val o3_mini_LIMITS = ModelLimits(
            rate = o3_mini_RATE_LIMITS,
            token = o3_mini_TOKEN_LIMITS,
        )

        private val GPT_5_1_LIMITS = ModelLimits(
            rate = GPT_5_1_RATE_LIMITS,
            token = GPT_5_1_TOKEN_LIMITS,
        )

        private val GPT_5_4_LIMITS = ModelLimits(
            rate = GPT_5_4_RATE_LIMITS,
            token = GPT_5_4_TOKEN_LIMITS,
        )

        private val GPT_5_4_mini_LIMITS = ModelLimits(
            rate = GPT_5_4_mini_RATE_LIMITS,
            token = GPT_5_4_mini_TOKEN_LIMITS,
        )

        private val GPT_5_5_LIMITS = ModelLimits(
            rate = GPT_5_5_RATE_LIMITS,
            token = GPT_5_5_TOKEN_LIMITS,
        )

        private val GPT_5_6_Sol_LIMITS = ModelLimits(
            rate = GPT_5_6_Sol_RATE_LIMITS,
            token = GPT_5_6_Sol_TOKEN_LIMITS,
        )

        // ---- Training Cutoffs ----

        private val GPT_5_CUTOFF = GMTDate(
            year = 2024,
            month = Month.SEPTEMBER,
            dayOfMonth = 30,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val GPT_5_mini_CUTOFF = GMTDate(
            year = 2024,
            month = Month.MAY,
            dayOfMonth = 31,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val GPT_5_nano_CUTOFF = GPT_5_mini_CUTOFF

        private val GPT_4_1_CUTOFF = GMTDate(
            year = 2024,
            month = Month.JUNE,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val GPT_4_1_mini_CUTOFF = GPT_4_1_CUTOFF

        private val GPT_4o_CUTOFF = GMTDate(
            year = 2023,
            month = Month.OCTOBER,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val GPT_4o_mini_CUTOFF = GPT_4o_CUTOFF

        private val o4_mini_CUTOFF = GMTDate(
            year = 2024,
            month = Month.JUNE,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val o3_CUTOFF = o4_mini_CUTOFF

        private val o3_mini_CUTOFF = GMTDate(
            year = 2024,
            month = Month.OCTOBER,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val GPT_5_1_CUTOFF = GMTDate(
            year = 2025,
            month = Month.OCTOBER,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val GPT_5_4_CUTOFF = GMTDate(
            year = 2025,
            month = Month.AUGUST,
            dayOfMonth = 31,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val GPT_5_4_mini_CUTOFF = GPT_5_4_CUTOFF

        private val GPT_5_5_CUTOFF = GMTDate(
            year = 2025,
            month = Month.DECEMBER,
            dayOfMonth = 1,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        private val GPT_5_6_Sol_CUTOFF = GMTDate(
            year = 2026,
            month = Month.FEBRUARY,
            dayOfMonth = 16,
            hours = 0,
            minutes = 0,
            seconds = 0,
        )

        // ---- Supported Inputs ----

        private val GPT_5_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_5_mini_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_5_nano_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_4_1_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_4_1_mini_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_4o_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_4o_mini_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val o4_mini_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val o3_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val o3_mini_SUPPORTED_INPUTS = TEXT

        private val GPT_5_1_SUPPORTED_INPUTS = TEXT_AND_IMAGE

        private val GPT_5_4_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_5_4_mini_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_5_5_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_5_6_Sol_SUPPORTED_INPUTS = TEXT_AND_IMAGE

        // ---- Model Features ----

        // OpenAI: "The following parameters are only supported when using
        // GPT-5.4 with reasoning effort set to `none`: temperature, top_p,
        // logprobs. Requests that include these fields will raise an error ...
        // for older GPT-5 models such as gpt-5, gpt-5-mini, or gpt-5-nano."
        // gpt-5/-mini/-nano reject them outright; gpt-5.1 and gpt-5.4 accept
        // them at their default effort (`none`), so those keep sampling on.
        // Omitting the fields is accepted at every effort, so where the docs are
        // silent (gpt-5.5, gpt-5.6-sol, the o-series) we omit rather than risk
        // an error.

        private val GPT_5_FEATURES = AIModelFeatures(
            availableTools = GPT_5_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.SLOW,
            supportedInputs = GPT_5_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_CUTOFF,
            supportsSamplingParameters = false,
        )

        private val GPT_5_mini_FEATURES = AIModelFeatures(
            availableTools = GPT_5_mini_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.NORMAL,
            speed = AIModelFeatures.RelativeSpeed.FAST,
            supportedInputs = GPT_5_mini_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_mini_CUTOFF,
            supportsSamplingParameters = false,
        )

        private val GPT_5_nano_FEATURES = AIModelFeatures(
            availableTools = GPT_5_nano_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.LOW,
            speed = AIModelFeatures.RelativeSpeed.FAST,
            supportedInputs = GPT_5_nano_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_nano_CUTOFF,
            supportsSamplingParameters = false,
        )

        private val GPT_4_1_FEATURES = AIModelFeatures(
            availableTools = GPT_4_1_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.NORMAL,
            supportedInputs = GPT_4_1_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_4_1_CUTOFF,
        )

        private val GPT_4_1_mini_FEATURES = AIModelFeatures(
            availableTools = GPT_4_1_mini_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.NORMAL,
            speed = AIModelFeatures.RelativeSpeed.FAST,
            supportedInputs = GPT_4_1_mini_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_4_1_mini_CUTOFF,
        )

        private val GPT_4o_FEATURES = AIModelFeatures(
            availableTools = GPT_4o_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.NORMAL,
            speed = AIModelFeatures.RelativeSpeed.NORMAL,
            supportedInputs = GPT_4o_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_4o_CUTOFF,
        )

        private val GPT_4o_mini_FEATURES = AIModelFeatures(
            availableTools = GPT_4o_mini_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.LOW,
            speed = AIModelFeatures.RelativeSpeed.FAST,
            supportedInputs = GPT_4o_mini_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_4o_mini_CUTOFF,
        )

        private val o4_mini_FEATURES = AIModelFeatures(
            availableTools = o4_mini_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.NORMAL,
            supportedInputs = o4_mini_SUPPORTED_INPUTS,
            trainingCutoffDate = o4_mini_CUTOFF,
            supportsSamplingParameters = false,
        )

        private val o3_FEATURES = AIModelFeatures(
            availableTools = o3_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.SLOW,
            supportedInputs = o3_SUPPORTED_INPUTS,
            trainingCutoffDate = o3_CUTOFF,
            supportsSamplingParameters = false,
        )

        private val o3_mini_FEATURES = AIModelFeatures(
            availableTools = o3_mini_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.NORMAL,
            speed = AIModelFeatures.RelativeSpeed.FAST,
            supportedInputs = o3_mini_SUPPORTED_INPUTS,
            trainingCutoffDate = o3_mini_CUTOFF,
            supportsSamplingParameters = false,
        )

        private val GPT_5_1_FEATURES = AIModelFeatures(
            availableTools = GPT_5_1_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.SLOW,
            supportedInputs = GPT_5_1_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_1_CUTOFF,
        )

        // GPT-5.2 onward accepts `temperature`/`top_p` only at reasoning effort
        // `none`, and Ampere doesn't send an effort — so the per-model default
        // decides. 5.4 and 5.4-mini default to `none` and keep sampling; 5.5 and
        // 5.6 Sol default to `medium`, where the docs are silent, so they omit.
        private val GPT_5_4_FEATURES = AIModelFeatures(
            availableTools = GPT_5_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.NORMAL,
            supportedInputs = GPT_5_4_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_4_CUTOFF,
        )

        private val GPT_5_4_mini_FEATURES = AIModelFeatures(
            availableTools = GPT_5_mini_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.NORMAL,
            speed = AIModelFeatures.RelativeSpeed.FAST,
            supportedInputs = GPT_5_4_mini_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_4_mini_CUTOFF,
        )

        private val GPT_5_5_FEATURES = AIModelFeatures(
            availableTools = GPT_5_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.SLOW,
            supportedInputs = GPT_5_5_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_5_CUTOFF,
            supportsSamplingParameters = false,
        )

        private val GPT_5_6_Sol_FEATURES = AIModelFeatures(
            availableTools = GPT_5_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.SLOW,
            supportedInputs = GPT_5_6_Sol_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_6_Sol_CUTOFF,
            supportsSamplingParameters = false,
        )

        // ---- Model Names ----

        private const val GPT_5_NAME = "gpt-5"
        private const val GPT_5_DISPLAY_NAME = "GPT-5"
        private const val GPT_5_DESCRIPTION =
            "GPT-5 is our flagship model for coding, reasoning, and agentic " +
                "tasks across domains."

        private const val GPT_5_mini_NAME = "gpt-5-mini"
        private const val GPT_5_mini_DISPLAY_NAME = "GPT-5 mini"
        private const val GPT_5_mini_DESCRIPTION =
            "GPT-5 mini is a faster, more cost-efficient version of GPT-5. " +
                "It's great for well-defined tasks and precise prompts."

        private const val GPT_5_nano_NAME = "gpt-5-nano"
        private const val GPT_5_nano_DISPLAY_NAME = "GPT-5 nano"
        private const val GPT_5_nano_DESCRIPTION =
            "GPT-5 Nano is our fastest, cheapest version of GPT-5. " +
                "It's great for summarization and classification tasks."

        private const val GPT_4_1_NAME = "gpt-4.1"
        private const val GPT_4_1_DISPLAY_NAME = "GPT-4.1"
        private const val GPT_4_1_DESCRIPTION =
            "GPT-4.1 excels at instruction following and tool calling, with " +
                "broad knowledge across domains. It features a 1M token " +
                "context window, and low latency without a reasoning step."

        private const val GPT_4_1_mini_NAME = "gpt-4.1-mini"
        private const val GPT_4_1_mini_DISPLAY_NAME = "GPT-4.1 mini"
        private const val GPT_4_1_mini_DESCRIPTION =
            "GPT-4.1 mini excels at instruction following and tool calling. " +
                "It features a 1M token context window, and low latency without a reasoning step."

        private const val GPT_4o_NAME = "gpt-4o"
        private const val GPT_4o_DISPLAY_NAME = "GPT-4o"
        private const val GPT_4o_DESCRIPTION =
            "GPT-4o (“o” for “omni”) is our versatile, high-intelligence " +
                "flagship model. It accepts both text and image inputs, and " +
                "produces text outputs (including Structured Outputs)."

        private const val GPT_4o_mini_NAME = "gpt-4o-mini"
        private const val GPT_4o_mini_DISPLAY_NAME = "GPT-4o mini"
        private const val GPT_4o_mini_DESCRIPTION =
            "GPT-4o mini (“o” for “omni”) is a fast, affordable small model " +
                "for focused tasks. It accepts both text and image inputs, and " +
                "produces text outputs (including Structured Outputs)."

        private const val o4_mini_NAME = "o4-mini"
        private const val o4_mini_DISPLAY_NAME = "04-mini"
        private const val o4_mini_DESCRIPTION =
            "o4-mini is our latest small o-series model. It's optimized for " +
                "fast, effective reasoning with exceptionally efficient performance in coding and visual tasks."

        private const val o3_NAME = "o3"
        private const val o3_DISPLAY_NAME = "o3"
        private const val o3_DESCRIPTION =
            "o3 is a well-rounded and powerful model across domains. " +
                "It sets a new standard for math, science, coding, and visual " +
                "reasoning tasks. It also excels at technical writing and " +
                "instruction-following. Use it to think through multi-step " +
                "problems that involve analysis across text, code, and images."

        private const val o3_mini_NAME = "o3-mini"
        private const val o3_mini_DISPLAY_NAME = "o3-mini"
        private const val o3_mini_DESCRIPTION =
            "o3-mini is our newest small reasoning model, providing high " +
                "intelligence at the same cost and latency targets of o1-mini. " +
                "o3-mini supports key developer features, like Structured Outputs, function calling, and Batch API."

        private const val GPT_5_1_NAME = "gpt-5.1"
        private const val GPT_5_1_DISPLAY_NAME = "GPT-5.1"
        private const val GPT_5_1_DESCRIPTION =
            "GPT-5.1 is OpenAI's flagship model with configurable reasoning " +
                "modes and enhanced capabilities for coding and agentic tasks."

        private const val GPT_5_4_NAME = "gpt-5.4"
        private const val GPT_5_4_DISPLAY_NAME = "GPT-5.4"
        private const val GPT_5_4_DESCRIPTION =
            "GPT-5.4 is a frontier reasoning model for coding and agentic tasks with a 1M token context window."

        private const val GPT_5_4_mini_NAME = "gpt-5.4-mini"
        private const val GPT_5_4_mini_DISPLAY_NAME = "GPT-5.4 mini"
        private const val GPT_5_4_mini_DESCRIPTION =
            "A faster, cost-efficient version of GPT-5.4 for well-defined tasks."

        private const val GPT_5_5_NAME = "gpt-5.5"
        private const val GPT_5_5_DISPLAY_NAME = "GPT-5.5"
        private const val GPT_5_5_DESCRIPTION =
            "GPT-5.5 is a frontier reasoning model for complex coding, agentic, and professional work."

        // Pinned rather than the `gpt-5.6` alias, which OpenAI routes to Sol today
        // but can repoint.
        private const val GPT_5_6_Sol_NAME = "gpt-5.6-sol"
        private const val GPT_5_6_Sol_DISPLAY_NAME = "GPT-5.6 Sol"
        private const val GPT_5_6_Sol_DESCRIPTION =
            "GPT-5.6 Sol is the flagship of the GPT-5.6 family for reasoning-heavy coding and agentic work."

        // ---- Models ----

        // Lazy to avoid the JVM class-init cycle: if any data object (GPT_4_1, etc.)
        // is accessed before this companion finishes initializing, the companion's
        // <clinit> would see that object's INSTANCE as null and commit a list with
        // null entries. Lazy defers evaluation until the companion is fully set up.
        val ALL_MODELS by lazy {
            listOf(
                GPT_5,
                GPT_5_mini,
                GPT_5_nano,
                GPT_5_1,
                GPT_5_4,
                GPT_5_4_mini,
                GPT_5_5,
                GPT_5_6_Sol,
                GPT_4_1,
                GPT_4_1_mini,
                GPT_4o,
                GPT_4o_mini,
                o4_mini,
                o3,
                o3_mini,
            )
        }
    }
}
