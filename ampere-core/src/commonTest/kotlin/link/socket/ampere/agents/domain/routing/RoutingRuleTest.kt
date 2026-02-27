package link.socket.ampere.agents.domain.routing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeSpeed
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class RoutingRuleTest {

    private val claudeConfig = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4,
    )

    private val geminiConfig = AIConfiguration_Default(
        provider = AIProvider_Google,
        model = AIModel_Gemini.Flash_2_5,
    )

    private val openaiConfig = AIConfiguration_Default(
        provider = AIProvider_OpenAI,
        model = AIModel_OpenAI.GPT_4_1,
    )

    // --- ByPhase ---

    @Test
    fun `ByPhase matches correct phase`() {
        val rule = RoutingRule.ByPhase(
            phase = CognitivePhase.PERCEIVE,
            configuration = geminiConfig,
        )
        val context = RoutingContext(phase = CognitivePhase.PERCEIVE)
        assertTrue(rule.matches(context))
    }

    @Test
    fun `ByPhase does not match different phase`() {
        val rule = RoutingRule.ByPhase(
            phase = CognitivePhase.PERCEIVE,
            configuration = geminiConfig,
        )
        val context = RoutingContext(phase = CognitivePhase.EXECUTE)
        assertFalse(rule.matches(context))
    }

    @Test
    fun `ByPhase does not match null phase`() {
        val rule = RoutingRule.ByPhase(
            phase = CognitivePhase.PERCEIVE,
            configuration = geminiConfig,
        )
        val context = RoutingContext(phase = null)
        assertFalse(rule.matches(context))
    }

    // --- ByAgent ---

    @Test
    fun `ByAgent matches correct agent`() {
        val rule = RoutingRule.ByAgent(
            agentId = "agent-code-1",
            configuration = claudeConfig,
        )
        val context = RoutingContext(agentId = "agent-code-1")
        assertTrue(rule.matches(context))
    }

    @Test
    fun `ByAgent does not match different agent`() {
        val rule = RoutingRule.ByAgent(
            agentId = "agent-code-1",
            configuration = claudeConfig,
        )
        val context = RoutingContext(agentId = "agent-product-2")
        assertFalse(rule.matches(context))
    }

    // --- ByRole ---

    @Test
    fun `ByRole matches correct role`() {
        val rule = RoutingRule.ByRole(
            roleName = "CodeAgent",
            configuration = claudeConfig,
        )
        val context = RoutingContext(agentRole = "CodeAgent")
        assertTrue(rule.matches(context))
    }

    @Test
    fun `ByRole does not match different role`() {
        val rule = RoutingRule.ByRole(
            roleName = "CodeAgent",
            configuration = claudeConfig,
        )
        val context = RoutingContext(agentRole = "ProductAgent")
        assertFalse(rule.matches(context))
    }

    @Test
    fun `ByRole does not match null role`() {
        val rule = RoutingRule.ByRole(
            roleName = "CodeAgent",
            configuration = claudeConfig,
        )
        val context = RoutingContext(agentRole = null)
        assertFalse(rule.matches(context))
    }

    // --- ByFeatures ---

    @Test
    fun `ByFeatures matches reasoning level`() {
        val rule = RoutingRule.ByFeatures(
            reasoning = RelativeReasoning.HIGH,
            configuration = openaiConfig,
        )
        val context = RoutingContext(preferredReasoning = RelativeReasoning.HIGH)
        assertTrue(rule.matches(context))
    }

    @Test
    fun `ByFeatures matches speed level`() {
        val rule = RoutingRule.ByFeatures(
            speed = RelativeSpeed.FAST,
            configuration = geminiConfig,
        )
        val context = RoutingContext(preferredSpeed = RelativeSpeed.FAST)
        assertTrue(rule.matches(context))
    }

    @Test
    fun `ByFeatures matches both reasoning and speed`() {
        val rule = RoutingRule.ByFeatures(
            reasoning = RelativeReasoning.HIGH,
            speed = RelativeSpeed.SLOW,
            configuration = openaiConfig,
        )
        val context = RoutingContext(
            preferredReasoning = RelativeReasoning.HIGH,
            preferredSpeed = RelativeSpeed.SLOW,
        )
        assertTrue(rule.matches(context))
    }

    @Test
    fun `ByFeatures does not match when reasoning differs`() {
        val rule = RoutingRule.ByFeatures(
            reasoning = RelativeReasoning.HIGH,
            configuration = openaiConfig,
        )
        val context = RoutingContext(preferredReasoning = RelativeReasoning.LOW)
        assertFalse(rule.matches(context))
    }

    @Test
    fun `ByFeatures does not match when both criteria are null`() {
        val rule = RoutingRule.ByFeatures(
            reasoning = null,
            speed = null,
            configuration = openaiConfig,
        )
        val context = RoutingContext(preferredReasoning = RelativeReasoning.HIGH)
        assertFalse(rule.matches(context))
    }

    // --- ByTag ---

    @Test
    fun `ByTag matches when tag is present`() {
        val rule = RoutingRule.ByTag(
            tag = "code-generation",
            configuration = claudeConfig,
        )
        val context = RoutingContext(tags = setOf("code-generation", "kotlin"))
        assertTrue(rule.matches(context))
    }

    @Test
    fun `ByTag does not match when tag is absent`() {
        val rule = RoutingRule.ByTag(
            tag = "code-generation",
            configuration = claudeConfig,
        )
        val context = RoutingContext(tags = setOf("summarization"))
        assertFalse(rule.matches(context))
    }

    @Test
    fun `ByTag does not match empty tags`() {
        val rule = RoutingRule.ByTag(
            tag = "code-generation",
            configuration = claudeConfig,
        )
        val context = RoutingContext(tags = emptySet())
        assertFalse(rule.matches(context))
    }

    // --- describeRule ---

    @Test
    fun `describeRule returns readable descriptions`() {
        assertTrue(
            RoutingRule.ByPhase(CognitivePhase.EXECUTE, claudeConfig)
                .describeRule()
                .contains("phase:EXECUTE"),
        )
        assertTrue(
            RoutingRule.ByAgent("agent-1", claudeConfig)
                .describeRule()
                .contains("agent:agent-1"),
        )
        assertTrue(
            RoutingRule.ByRole("CodeAgent", claudeConfig)
                .describeRule()
                .contains("role:CodeAgent"),
        )
        assertTrue(
            RoutingRule.ByTag("code-gen", claudeConfig)
                .describeRule()
                .contains("tag:code-gen"),
        )
        assertTrue(
            RoutingRule.ByFeatures(reasoning = RelativeReasoning.HIGH, configuration = claudeConfig)
                .describeRule()
                .contains("reasoning=HIGH"),
        )
    }
}
