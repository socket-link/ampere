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

    data object GPT_5_1_Chat_Latest : AIModel_OpenAI(
        name = GPT_5_1_Chat_Latest_NAME,
        displayName = GPT_5_1_Chat_Latest_DISPLAY_NAME,
        description = GPT_5_1_Chat_Latest_DESCRIPTION,
        features = GPT_5_1_Chat_Latest_FEATURES,
        limits = GPT_5_1_Chat_Latest_LIMITS,
    )

    data object GPT_5_1_Codex_Max : AIModel_OpenAI(
        name = GPT_5_1_Codex_Max_NAME,
        displayName = GPT_5_1_Codex_Max_DISPLAY_NAME,
        description = GPT_5_1_Codex_Max_DESCRIPTION,
        features = GPT_5_1_Codex_Max_FEATURES,
        limits = GPT_5_1_Codex_Max_LIMITS,
    )

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
        private val GPT_5_1_Chat_Latest_TOOLS: List<ProvidedTool<AITool_OpenAI>> = GPT_5_TOOLS
        private val GPT_5_1_Codex_Max_TOOLS: List<ProvidedTool<AITool_OpenAI>> = listOf(
            ProvidedTool.CodeExecution(AITool_OpenAI.CodeExecution),
            ProvidedTool.FileSearch(AITool_OpenAI.FileSearch),
            ProvidedTool.MCP(AITool_OpenAI.MCP),
        )

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
        private val GPT_5_1_Chat_Latest_RATE_LIMITS = GPT_5_RATE_LIMITS
        private val GPT_5_1_Codex_Max_RATE_LIMITS = nonFreeRateLimitsFactory.createRateLimits(
            tier1TPM = TokenCount._30k,
            tier2TPM = TokenCount._450k,
            tier3TPM = TokenCount._800k,
            tier4TPM = TokenCount._2m,
            tier5TPM = TokenCount._40m,
        )

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
        private val GPT_5_1_Chat_Latest_TOKEN_LIMITS = GPT_5_TOKEN_LIMITS
        private val GPT_5_1_Codex_Max_TOKEN_LIMITS = GPT_5_TOKEN_LIMITS

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

        private val GPT_5_1_Chat_Latest_LIMITS = ModelLimits(
            rate = GPT_5_1_Chat_Latest_RATE_LIMITS,
            token = GPT_5_1_Chat_Latest_TOKEN_LIMITS,
        )

        private val GPT_5_1_Codex_Max_LIMITS = ModelLimits(
            rate = GPT_5_1_Codex_Max_RATE_LIMITS,
            token = GPT_5_1_Codex_Max_TOKEN_LIMITS,
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

        private val GPT_5_1_Chat_Latest_CUTOFF = GPT_5_1_CUTOFF
        private val GPT_5_1_Codex_Max_CUTOFF = GPT_5_1_CUTOFF

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
        private val GPT_5_1_Chat_Latest_SUPPORTED_INPUTS = TEXT_AND_IMAGE
        private val GPT_5_1_Codex_Max_SUPPORTED_INPUTS = TEXT_AND_IMAGE

        // ---- Model Features ----

        private val GPT_5_FEATURES = AIModelFeatures(
            availableTools = GPT_5_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.SLOW,
            supportedInputs = GPT_5_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_CUTOFF,
        )

        private val GPT_5_mini_FEATURES = AIModelFeatures(
            availableTools = GPT_5_mini_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.NORMAL,
            speed = AIModelFeatures.RelativeSpeed.NORMAL,
            supportedInputs = GPT_5_mini_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_mini_CUTOFF,
        )

        private val GPT_5_nano_FEATURES = AIModelFeatures(
            availableTools = GPT_5_nano_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.NORMAL,
            speed = AIModelFeatures.RelativeSpeed.FAST,
            supportedInputs = GPT_5_nano_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_nano_CUTOFF,
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
        )

        private val o3_FEATURES = AIModelFeatures(
            availableTools = o3_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.SLOW,
            supportedInputs = o3_SUPPORTED_INPUTS,
            trainingCutoffDate = o3_CUTOFF,
        )

        private val o3_mini_FEATURES = AIModelFeatures(
            availableTools = o3_mini_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.NORMAL,
            supportedInputs = o3_mini_SUPPORTED_INPUTS,
            trainingCutoffDate = o3_mini_CUTOFF,
        )

        private val GPT_5_1_FEATURES = AIModelFeatures(
            availableTools = GPT_5_1_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.SLOW,
            supportedInputs = GPT_5_1_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_1_CUTOFF,
        )

        private val GPT_5_1_Chat_Latest_FEATURES = AIModelFeatures(
            availableTools = GPT_5_1_Chat_Latest_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.FAST,
            supportedInputs = GPT_5_1_Chat_Latest_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_1_Chat_Latest_CUTOFF,
        )

        private val GPT_5_1_Codex_Max_FEATURES = AIModelFeatures(
            availableTools = GPT_5_1_Codex_Max_TOOLS,
            reasoningLevel = AIModelFeatures.RelativeReasoning.HIGH,
            speed = AIModelFeatures.RelativeSpeed.NORMAL,
            supportedInputs = GPT_5_1_Codex_Max_SUPPORTED_INPUTS,
            trainingCutoffDate = GPT_5_1_Codex_Max_CUTOFF,
        )

        // ---- Model Names ----

        private const val GPT_5_NAME = "gpt-5"
        private const val GPT_5_DISPLAY_NAME = "GPT-5"
        private const val GPT_5_DESCRIPTION = "GPT-5 is our flagship model for coding, reasoning, and agentic tasks across domains."

        private const val GPT_5_mini_NAME = "gpt-5-mini"
        private const val GPT_5_mini_DISPLAY_NAME = "GPT-5 mini"
        private const val GPT_5_mini_DESCRIPTION = "GPT-5 mini is a faster, more cost-efficient version of GPT-5. It's great for well-defined tasks and precise prompts."

        private const val GPT_5_nano_NAME = "gpt-5-nano"
        private const val GPT_5_nano_DISPLAY_NAME = "GPT-5 nano"
        private const val GPT_5_nano_DESCRIPTION = "GPT-5 Nano is our fastest, cheapest version of GPT-5. It's great for summarization and classification tasks."

        private const val GPT_4_1_NAME = "gpt-4.1"
        private const val GPT_4_1_DISPLAY_NAME = "GPT-4.1"
        private const val GPT_4_1_DESCRIPTION = "GPT-4.1 excels at instruction following and tool calling, with broad knowledge across domains. It features a 1M token context window, and low latency without a reasoning step."

        private const val GPT_4_1_mini_NAME = "gpt-4.1-mini"
        private const val GPT_4_1_mini_DISPLAY_NAME = "GPT-4.1 mini"
        private const val GPT_4_1_mini_DESCRIPTION = "GPT-4.1 mini excels at instruction following and tool calling. It features a 1M token context window, and low latency without a reasoning step."

        private const val GPT_4o_NAME = "gpt-4o"
        private const val GPT_4o_DISPLAY_NAME = "GPT-4o"
        private const val GPT_4o_DESCRIPTION = "GPT-4o (“o” for “omni”) is our versatile, high-intelligence flagship model. It accepts both text and image inputs, and produces text outputs (including Structured Outputs)."

        private const val GPT_4o_mini_NAME = "gpt-4o-mini"
        private const val GPT_4o_mini_DISPLAY_NAME = "GPT-4o mini"
        private const val GPT_4o_mini_DESCRIPTION = "GPT-4o mini (“o” for “omni”) is a fast, affordable small model for focused tasks. It accepts both text and image inputs, and produces text outputs (including Structured Outputs)."

        private const val o4_mini_NAME = "o4-mini"
        private const val o4_mini_DISPLAY_NAME = "04-mini"
        private const val o4_mini_DESCRIPTION = "o4-mini is our latest small o-series model. It's optimized for fast, effective reasoning with exceptionally efficient performance in coding and visual tasks."

        private const val o3_NAME = "o3"
        private const val o3_DISPLAY_NAME = "o3"
        private const val o3_DESCRIPTION = "o3 is a well-rounded and powerful model across domains. It sets a new standard for math, science, coding, and visual reasoning tasks. It also excels at technical writing and instruction-following. Use it to think through multi-step problems that involve analysis across text, code, and images."

        private const val o3_mini_NAME = "o3-mini"
        private const val o3_mini_DISPLAY_NAME = "o3-mini"
        private const val o3_mini_DESCRIPTION = "o3-mini is our newest small reasoning model, providing high intelligence at the same cost and latency targets of o1-mini. o3-mini supports key developer features, like Structured Outputs, function calling, and Batch API."

        private const val GPT_5_1_NAME = "gpt-5.1"
        private const val GPT_5_1_DISPLAY_NAME = "GPT-5.1"
        private const val GPT_5_1_DESCRIPTION = "GPT-5.1 is OpenAI's flagship model with configurable reasoning modes and enhanced capabilities for coding and agentic tasks."

        private const val GPT_5_1_Chat_Latest_NAME = "gpt-5.1-chat-latest"
        private const val GPT_5_1_Chat_Latest_DISPLAY_NAME = "GPT-5.1 Instant"
        private const val GPT_5_1_Chat_Latest_DESCRIPTION = "GPT-5.1 Instant uses adaptive reasoning to decide when to think before responding, offering fast responses while maintaining accuracy on complex tasks."

        private const val GPT_5_1_Codex_Max_NAME = "gpt-5.1-codex-max"
        private const val GPT_5_1_Codex_Max_DISPLAY_NAME = "GPT-5.1 Codex Max"
        private const val GPT_5_1_Codex_Max_DESCRIPTION = "GPT-5.1 Codex Max is a specialized model for agentic coding tasks, featuring compaction technology for working across millions of tokens in long-running agent loops."

        // ---- Models ----

        val ALL_MODELS = listOf(
            GPT_5,
            GPT_5_mini,
            GPT_5_nano,
            GPT_5_1,
            GPT_5_1_Chat_Latest,
            GPT_5_1_Codex_Max,
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
