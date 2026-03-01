package link.socket.ampere.agents.domain.reasoning

import ai.koog.prompt.message.ResponseMetaInfo
import com.aallam.openai.api.core.Usage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.api.model.TokenUsage

/**
 * Maps provider-specific usage metadata into AMPERE's stable [TokenUsage] contract.
 *
 * Cost remains nullable because not every provider reports it, and AMPERE does not
 * currently maintain local pricing tables for inference.
 */
internal object TokenUsageExtractor {

    fun fromOpenAiUsage(usage: Usage?): TokenUsage = usage?.let {
        TokenUsage(
            inputTokens = it.promptTokens,
            outputTokens = it.completionTokens,
        )
    } ?: TokenUsage()

    fun fromResponseMetaInfo(metaInfo: ResponseMetaInfo?): TokenUsage = metaInfo?.let {
        TokenUsage(
            inputTokens = it.inputTokensCount,
            outputTokens = it.outputTokensCount,
            estimatedCost = extractEstimatedCost(it.metadata),
        )
    } ?: TokenUsage()

    private fun extractEstimatedCost(metadata: JsonObject?): Double? {
        if (metadata == null) return null

        val candidateKeys = listOf(
            "estimatedCost",
            "estimated_cost",
            "costUsd",
            "cost_usd",
            "cost",
        )

        for (key in candidateKeys) {
            val value = metadata[key]?.jsonPrimitive?.doubleOrNull
            if (value != null) return value
        }

        return null
    }
}
