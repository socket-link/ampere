package link.socket.ampere.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
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

/**
 * Integration tests for custom LLM provider functionality.
 *
 * These tests verify:
 * 1. Custom provider is called when configured
 * 2. Prompts contain expected content (system message + user prompt)
 * 3. Fallback to built-in provider when no custom provider is configured
 * 4. Combined prompt format is correct
 */
class CustomLlmProviderIntegrationTest {

    /**
     * Fake AI configuration for testing that doesn't make real API calls.
     */
    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Provider should not be called when custom provider is set")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    /**
     * Test that custom provider is called when configured.
     */
    @Test
    fun `custom provider is called when configured`() = runTest {
        var providerCalled = false
        var receivedPrompt: String? = null

        val customProvider: LlmProvider = { prompt ->
            providerCalled = true
            receivedPrompt = prompt
            "Mock response from custom provider"
        }

        val config = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveConfig = CognitiveConfig(),
            llmProvider = customProvider,
        )

        val llmService = AgentLLMService(config)

        val response = llmService.call(
            prompt = "What is 2 + 2?",
            systemMessage = "You are a calculator.",
        )

        assertTrue(providerCalled, "Custom provider should have been called")
        assertEquals("Mock response from custom provider", response)
        assertContains(receivedPrompt!!, "You are a calculator.")
        assertContains(receivedPrompt!!, "What is 2 + 2?")
    }

    /**
     * Test that combined prompt format is correct.
     */
    @Test
    fun `combined prompt has correct format`() = runTest {
        var receivedPrompt: String? = null

        val customProvider: LlmProvider = { prompt ->
            receivedPrompt = prompt
            "Response"
        }

        val config = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveConfig = CognitiveConfig(),
            llmProvider = customProvider,
        )

        val llmService = AgentLLMService(config)

        llmService.call(
            prompt = "Hello world",
            systemMessage = "Be helpful",
        )

        // Verify format: System: ... \n\n User: ...
        assertTrue(receivedPrompt!!.startsWith("System: Be helpful"))
        assertTrue(receivedPrompt!!.contains("\n\nUser: Hello world"))
    }

    /**
     * Test buildCombinedPrompt utility function directly.
     */
    @Test
    fun `buildCombinedPrompt creates correct format`() {
        val combined = AgentLLMService.buildCombinedPrompt(
            systemMessage = "You are helpful",
            prompt = "What time is it?",
        )

        assertEquals(
            """
            |System: You are helpful
            |
            |User: What time is it?
            """.trimMargin(),
            combined,
        )
    }

    /**
     * Test that prompts contain expected content for JSON calls.
     */
    @Test
    fun `callForJson includes JSON system message`() = runTest {
        var receivedPrompt: String? = null

        val customProvider: LlmProvider = { prompt ->
            receivedPrompt = prompt
            """{"result": "success"}"""
        }

        val config = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveConfig = CognitiveConfig(),
            llmProvider = customProvider,
        )

        val llmService = AgentLLMService(config)

        val jsonResponse = llmService.callForJson(
            prompt = "Return status",
        )

        // Verify JSON system message was used
        assertContains(receivedPrompt!!, "valid JSON")

        // Verify response was parsed
        val obj = jsonResponse.asObject()
        assertEquals("success", obj["result"]?.toString()?.trim('"'))
    }

    /**
     * Test that custom provider can return different responses based on prompt.
     */
    @Test
    fun `custom provider can implement conditional logic`() = runTest {
        val customProvider: LlmProvider = { prompt ->
            when {
                prompt.contains("greeting") -> "Hello!"
                prompt.contains("farewell") -> "Goodbye!"
                else -> "Unknown request"
            }
        }

        val config = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveConfig = CognitiveConfig(),
            llmProvider = customProvider,
        )

        val llmService = AgentLLMService(config)

        assertEquals("Hello!", llmService.call("Say a greeting"))
        assertEquals("Goodbye!", llmService.call("Say a farewell"))
        assertEquals("Unknown request", llmService.call("Do something else"))
    }

    /**
     * Test that null provider falls back to built-in (would fail without real API).
     * This test verifies the code path, not the actual API call.
     */
    @Test
    fun `null provider attempts to use built-in provider`() = runTest {
        val config = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveConfig = CognitiveConfig(),
            llmProvider = null, // No custom provider
        )

        val llmService = AgentLLMService(config)

        // This will throw because FakeAIConfiguration throws on provider access
        // This verifies the fallback path is taken
        try {
            llmService.call("Test prompt")
            assertTrue(false, "Should have thrown when accessing fake provider")
        } catch (e: NotImplementedError) {
            // Expected - the fake provider throws this
            assertContains(e.message ?: "", "Provider should not be called")
        }
    }
}
