package link.socket.ampere.agents.domain.reasoning

import ai.koog.prompt.message.ResponseMetaInfo
import com.aallam.openai.api.core.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.api.model.TokenUsage

class TokenUsageExtractorTest {

    @Test
    fun `maps OpenAI usage into AMPERE token usage`() {
        val usage = Usage(
            promptTokens = 1247,
            completionTokens = 892,
            totalTokens = 2139,
        )

        val extracted = TokenUsageExtractor.fromOpenAiUsage(usage)

        assertEquals(
            TokenUsage(
                inputTokens = 1247,
                outputTokens = 892,
            ),
            extracted,
        )
    }

    @Test
    fun `maps response metadata into AMPERE token usage including cost`() {
        val metaInfo = ResponseMetaInfo.create(
            clock = Clock.System,
            totalTokensCount = 2139,
            inputTokensCount = 1247,
            outputTokensCount = 892,
            metadata = buildJsonObject {
                put("estimatedCost", 0.0031)
            },
        )

        val extracted = TokenUsageExtractor.fromResponseMetaInfo(metaInfo)

        assertEquals(
            TokenUsage(
                inputTokens = 1247,
                outputTokens = 892,
                estimatedCost = 0.0031,
            ),
            extracted,
        )
    }

    @Test
    fun `returns empty token usage when provider metadata is absent`() {
        assertEquals(TokenUsage(), TokenUsageExtractor.fromOpenAiUsage(null))
        assertEquals(TokenUsage(), TokenUsageExtractor.fromResponseMetaInfo(null))
    }
}
