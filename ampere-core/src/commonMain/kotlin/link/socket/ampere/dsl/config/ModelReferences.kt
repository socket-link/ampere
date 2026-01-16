package link.socket.ampere.dsl.config

import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

/**
 * Type-safe references to Claude models for use in DSL.
 *
 * Example:
 * ```kotlin
 * val config = AnthropicConfig(model = Claude.Sonnet4)
 * ```
 */
object Claude {
    val Opus4_5 = AIModel_Claude.Opus_4_5
    val Opus4_1 = AIModel_Claude.Opus_4_1
    val Opus4 = AIModel_Claude.Opus_4
    val Sonnet4_5 = AIModel_Claude.Sonnet_4_5
    val Sonnet4 = AIModel_Claude.Sonnet_4
    val Sonnet3_7 = AIModel_Claude.Sonnet_3_7
    val Haiku4_5 = AIModel_Claude.Haiku_4_5
    val Haiku3_5 = AIModel_Claude.Haiku_3_5
    val Haiku3 = AIModel_Claude.Haiku_3
}

/**
 * Type-safe references to OpenAI GPT models for use in DSL.
 *
 * Example:
 * ```kotlin
 * val config = OpenAIConfig(model = GPT.GPT5)
 * ```
 */
object GPT {
    val GPT5_1 = AIModel_OpenAI.GPT_5_1
    val GPT5_1_Instant = AIModel_OpenAI.GPT_5_1_Chat_Latest
    val GPT5_1_CodexMax = AIModel_OpenAI.GPT_5_1_Codex_Max
    val GPT5 = AIModel_OpenAI.GPT_5
    val GPT5_mini = AIModel_OpenAI.GPT_5_mini
    val GPT5_nano = AIModel_OpenAI.GPT_5_nano
    val GPT4_1 = AIModel_OpenAI.GPT_4_1
    val GPT4_1_mini = AIModel_OpenAI.GPT_4_1_mini
    val GPT4o = AIModel_OpenAI.GPT_4o
    val GPT4o_mini = AIModel_OpenAI.GPT_4o_mini
    val O4_mini = AIModel_OpenAI.o4_mini
    val O3 = AIModel_OpenAI.o3
    val O3_mini = AIModel_OpenAI.o3_mini
}

/**
 * Type-safe references to Google Gemini models for use in DSL.
 *
 * Example:
 * ```kotlin
 * val config = GeminiConfig(model = Gemini.Pro3)
 * ```
 */
object Gemini {
    val Pro3 = AIModel_Gemini.Pro_3_0
    val Pro2_5 = AIModel_Gemini.Pro_2_5
    val Flash2_5 = AIModel_Gemini.Flash_2_5
    val Flash2_5_Lite = AIModel_Gemini.Flash_Lite_2_5
    val Flash2 = AIModel_Gemini.Flash_2_0
    val Flash2_Lite = AIModel_Gemini.Flash_Lite_2_0
}
