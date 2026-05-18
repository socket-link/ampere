package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.execution.tools.planning.PLAN_STEPS_TOOL_ID
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.llm.LlmProvider

class SparkBasedAgentActivePromptProviderTest {

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Provider should not be called when custom provider is set")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    @Test
    fun `callLLM payload reflects current spark stack at call time`() = runTest {
        val captured = CopyOnWriteArrayList<String>()
        val provider: LlmProvider = { prompt ->
            captured += prompt
            "ok"
        }
        val agent = SparkBasedAgent(
            agentId = "test-spark-agent",
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _aiConfiguration = FakeAIConfiguration(),
            _llmProvider = provider,
        )

        agent.callLLM("first")
        val firstPayload = captured.last()
        assertTrue(
            firstPayload.contains("# Cognitive Context"),
            "expected affinity header in payload, got: $firstPayload",
        )

        val roleCode = DefaultPhaseSparkLibrary.load().roleSparkById(SparkBasedAgent.ROLE_CODE_SPARK_ID)
            ?: error("role-code fixture missing")
        agent.spark<SparkBasedAgent<CodeState>>(roleCode)
        agent.callLLM("second")
        val secondPayload = captured.last()
        assertTrue(
            secondPayload.contains("## Role: Code"),
            "expected role-code contribution in payload after sparking, got: $secondPayload",
        )

        assertEquals(2, captured.size)
    }

    @Test
    fun `every SparkBasedAgent ships with the plan_steps tool by default`() {
        val agent = SparkBasedAgent(
            agentId = "default-tools-agent",
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _aiConfiguration = FakeAIConfiguration(),
        )

        val toolIds = agent.requiredTools.map { it.id }.toSet()
        assertTrue(
            PLAN_STEPS_TOOL_ID in toolIds,
            "Spark-based agents should include the plan_steps tool by default; got $toolIds",
        )
    }
}
