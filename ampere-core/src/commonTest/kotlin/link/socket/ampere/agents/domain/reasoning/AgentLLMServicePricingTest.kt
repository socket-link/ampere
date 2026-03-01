package link.socket.ampere.agents.domain.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class AgentLLMServicePricingTest {

    private val service = AgentLLMService(
        agentConfiguration = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_OpenAI,
                model = AIModel_OpenAI.GPT_4_1,
            ),
            cognitiveConfig = CognitiveConfig(),
        ),
    )

    @Test
    fun `enriches token usage with bundled cost for known model`() = runTest {
        val usage = service.enrichUsageWithEstimatedCost(
            providerId = "openai",
            modelId = "gpt-4.1",
            usage = TokenUsage(
                inputTokens = 1_000,
                outputTokens = 500,
            ),
        )

        assertEquals(0.006, assertNotNull(usage.estimatedCost), absoluteTolerance = 0.0000001)
    }

    @Test
    fun `preserves provider reported cost when bundled pricing is unavailable`() = runTest {
        val usage = service.enrichUsageWithEstimatedCost(
            providerId = "openai",
            modelId = "unknown-model",
            usage = TokenUsage(
                inputTokens = 1_000,
                outputTokens = 500,
                estimatedCost = 0.42,
            ),
        )

        assertEquals(0.42, usage.estimatedCost)
    }
}
