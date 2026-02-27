package link.socket.ampere.agents.domain.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google

class CognitiveRelayPassthroughTest {

    private val claudeConfig = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4,
    )

    private val geminiConfig = AIConfiguration_Default(
        provider = AIProvider_Google,
        model = AIModel_Gemini.Flash_2_5,
    )

    @Test
    fun `always returns fallback configuration`() = runTest {
        val context = RoutingContext(
            phase = CognitivePhase.PERCEIVE,
            agentId = "test-agent",
            agentRole = "CodeAgent",
        )

        val result = CognitiveRelayPassthrough.resolve(context, claudeConfig)
        assertEquals(AIModel_Claude.Sonnet_4, result.model)
    }

    @Test
    fun `ignores routing context entirely`() = runTest {
        val context = RoutingContext(
            phase = CognitivePhase.EXECUTE,
            agentId = "agent-1",
            agentRole = "ProductAgent",
            tags = setOf("code-generation"),
        )

        val result = CognitiveRelayPassthrough.resolve(context, geminiConfig)
        assertEquals(AIModel_Gemini.Flash_2_5, result.model)
    }

    @Test
    fun `config is always empty`() {
        assertTrue(CognitiveRelayPassthrough.config.rules.isEmpty())
        assertEquals(null, CognitiveRelayPassthrough.config.defaultConfiguration)
    }

    @Test
    fun `updateConfig is no-op`() = runTest {
        CognitiveRelayPassthrough.updateConfig(
            RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(CognitivePhase.EXECUTE, claudeConfig),
                ),
            ),
        )

        // Config remains empty after attempted update
        assertTrue(CognitiveRelayPassthrough.config.rules.isEmpty())
    }
}
