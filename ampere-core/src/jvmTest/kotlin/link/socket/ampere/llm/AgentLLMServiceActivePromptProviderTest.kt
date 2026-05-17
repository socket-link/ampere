package link.socket.ampere.llm

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.llm.LlmProvider

class AgentLLMServiceActivePromptProviderTest {

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Provider should not be called when custom provider is set")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    private fun configWithCapture(capture: (String) -> Unit): AgentConfiguration {
        val customProvider: LlmProvider = { prompt ->
            capture(prompt)
            "captured"
        }
        return AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveConfig = CognitiveConfig(),
            llmProvider = customProvider,
        )
    }

    @Test
    fun `absent activePromptProvider leaves system message unchanged`() = runTest {
        var captured: String? = null
        val service = AgentLLMService(
            agentConfiguration = configWithCapture { captured = it },
        )

        service.call(prompt = "user-prompt", systemMessage = "system-message")

        val received = requireNotNull(captured)
        assertContains(received, "System: system-message")
        assertFalse(
            received.contains("ACTIVE PROMPT"),
            "ACTIVE PROMPT should not appear when no provider supplied: $received",
        )
    }

    @Test
    fun `non-null activePromptProvider value is prepended to system message`() = runTest {
        var captured: String? = null
        val service = AgentLLMService(
            agentConfiguration = configWithCapture { captured = it },
            activePromptProvider = { "ACTIVE PROMPT" },
        )

        service.call(prompt = "user-prompt", systemMessage = "system-message")

        val received = requireNotNull(captured)
        val activeIndex = received.indexOf("ACTIVE PROMPT")
        val systemIndex = received.indexOf("system-message")
        assertContains(received, "ACTIVE PROMPT")
        assertContains(received, "system-message")
        assert(activeIndex >= 0 && systemIndex >= 0 && activeIndex < systemIndex) {
            "ACTIVE PROMPT must precede system-message in payload: $received"
        }
    }

    @Test
    fun `null from activePromptProvider leaves system message unchanged`() = runTest {
        var captured: String? = null
        val service = AgentLLMService(
            agentConfiguration = configWithCapture { captured = it },
            activePromptProvider = { null },
        )

        service.call(prompt = "user-prompt", systemMessage = "system-message")

        val received = requireNotNull(captured)
        assertContains(received, "System: system-message")
        assertFalse(
            received.contains("ACTIVE PROMPT"),
            "Provider returning null should be a no-op: $received",
        )
    }

    @Test
    fun `activePromptProvider is evaluated once per call, not cached`() = runTest {
        var invocationCount = 0
        var captured: String? = null
        val service = AgentLLMService(
            agentConfiguration = configWithCapture { captured = it },
            activePromptProvider = {
                invocationCount += 1
                "PROMPT-$invocationCount"
            },
        )

        service.call(prompt = "p1", systemMessage = "s")
        assertContains(requireNotNull(captured), "PROMPT-1")

        service.call(prompt = "p2", systemMessage = "s")
        assertContains(requireNotNull(captured), "PROMPT-2")

        assertEquals(2, invocationCount)
    }
}
