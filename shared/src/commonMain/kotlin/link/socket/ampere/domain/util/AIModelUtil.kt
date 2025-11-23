package link.socket.ampere.domain.util

import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import com.aallam.openai.api.model.ModelId as ClientModelId
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI

fun AIModel.toClientModelId(): ClientModelId =
    ClientModelId(name)

fun AIModel.toKoogLLMModel(): LLModel? = when (this) {
    is AIModel_OpenAI -> when (this) {
        is AIModel_OpenAI.GPT_5 -> OpenAIModels.Chat.GPT5
        is AIModel_OpenAI.GPT_5_mini -> OpenAIModels.Chat.GPT5Mini
        is AIModel_OpenAI.GPT_5_nano -> OpenAIModels.Chat.GPT5Nano
        is AIModel_OpenAI.GPT_4_1 -> OpenAIModels.Chat.GPT4_1
        is AIModel_OpenAI.GPT_4_1_mini -> OpenAIModels.CostOptimized.GPT4_1Mini
        is AIModel_OpenAI.GPT_4o -> OpenAIModels.Chat.GPT4o
        is AIModel_OpenAI.GPT_4o_mini -> OpenAIModels.CostOptimized.GPT4oMini
        is AIModel_OpenAI.o4_mini -> OpenAIModels.Reasoning.O4Mini
        is AIModel_OpenAI.o3 -> OpenAIModels.Reasoning.O3
        is AIModel_OpenAI.o3_mini -> OpenAIModels.Reasoning.O3Mini
    }
    is AIModel_Gemini -> when (this) {
        is AIModel_Gemini.Pro_2_5 -> GoogleModels.Gemini2_5Pro
        is AIModel_Gemini.Flash_2_5 -> GoogleModels.Gemini2_5Flash
        // is AIModel_Gemini.Flash_Lite_2_5 -> GoogleModels.Gemini2_5FlashLite
        is AIModel_Gemini.Flash_2_0 -> GoogleModels.Gemini2_0Flash
        is AIModel_Gemini.Flash_Lite_2_0 -> GoogleModels.Gemini2_0FlashLite
        else -> null
    }
    is AIModel_Claude -> when (this) {
        // is AIModel_Claude.Opus_4_1 -> AnthropicModels.Opus_4_1
        is AIModel_Claude.Opus_4 -> AnthropicModels.Opus_4
        is AIModel_Claude.Sonnet_4 -> AnthropicModels.Sonnet_4
        is AIModel_Claude.Sonnet_3_7 -> AnthropicModels.Sonnet_3_7
        is AIModel_Claude.Haiku_3_5 -> AnthropicModels.Haiku_3_5
        is AIModel_Claude.Haiku_3 -> AnthropicModels.Haiku_3
        else -> null
    }
}
