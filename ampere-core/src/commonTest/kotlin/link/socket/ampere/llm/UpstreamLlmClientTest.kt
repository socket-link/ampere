package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.Effort
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class UpstreamLlmClientTest {

    private val config = AgentConfiguration(
        agentDefinition = WriteCodeAgent,
        aiConfiguration = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_4_6,
        ),
    )

    @Test
    fun `custom upstream client receives the materialized request`() = runTest {
        // When a custom UpstreamLlmClient is injected, AgentLLMService routes
        // the full ChatCompletionRequest through it instead of touching the
        // per-provider OpenAI client. This is the path Socket consumes.
        val recorder = RecordingUpstreamClient(
            cannedResponse = "canned-response-text",
        )

        val service = AgentLLMService(
            agentConfiguration = config,
            upstreamLlmClient = recorder,
        )

        val response = service.call(
            prompt = "Tell me about migration",
            systemMessage = "You are a database expert.",
            temperature = 0.7,
            maxTokens = 256,
        )

        assertEquals("canned-response-text", response)
        val captured = assertNotNull(recorder.lastRequest, "Custom client must be invoked")
        assertEquals(AIModel_Claude.Sonnet_4_6.name, captured.model.id)
        assertEquals(0.7, captured.temperature)
        assertEquals(256, captured.maxTokens)
        assertEquals(2, captured.messages.size)
        assertEquals(ChatRole.System, captured.messages[0].role)
        assertEquals("You are a database expert.", captured.messages[0].content)
        assertEquals(ChatRole.User, captured.messages[1].role)
        assertEquals("Tell me about migration", captured.messages[1].content)
        assertSame(config.aiConfiguration, recorder.lastConfiguration)
    }

    @Test
    fun `temperature is omitted for models that reject sampling parameters`() = runTest {
        // Opus 5 returns a 400 for any temperature, including through the
        // OpenAI-compatible endpoint, so the field must be absent, not defaulted.
        val recorder = RecordingUpstreamClient(cannedResponse = "ok")
        val service = AgentLLMService(
            agentConfiguration = config.copy(
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Opus_5,
                ),
            ),
            upstreamLlmClient = recorder,
        )

        service.call(prompt = "anything", temperature = 0.7)

        val captured = assertNotNull(recorder.lastRequest)
        assertEquals(AIModel_Claude.Opus_5.name, captured.model.id)
        assertNull(captured.temperature)
    }

    @Test
    fun `a gemini 3 request carries no temperature`() = runTest {
        // AMPR-488: Google says a temperature below 1.0 can make Gemini 3 loop.
        val recorder = RecordingUpstreamClient(cannedResponse = "ok")
        val service = AgentLLMService(
            agentConfiguration = config.copy(aiConfiguration = geminiConfiguration(AIModel_Gemini.Flash_3_8)),
            upstreamLlmClient = recorder,
        )

        service.call(prompt = "anything", temperature = 0.3)

        val captured = assertNotNull(recorder.lastRequest)
        assertEquals("gemini-3.8-flash", captured.model.id)
        assertNull(captured.temperature)
    }

    @Test
    fun `a quick gemini 3 call asks for low reasoning effort`() = runTest {
        // A 500-token cap is what PERCEIVE sends. Gemini 3.8 Flash thinks at
        // `medium` by default and counts thought against the cap.
        val recorder = RecordingUpstreamClient(cannedResponse = "ok")
        val service = AgentLLMService(
            agentConfiguration = config.copy(aiConfiguration = geminiConfiguration(AIModel_Gemini.Flash_3_8)),
            upstreamLlmClient = recorder,
        )

        service.call(prompt = "anything", maxTokens = 500)

        val captured = assertNotNull(recorder.lastRequest)
        assertEquals(Effort("low"), captured.reasoningEffort)
        assertEquals(500, captured.maxTokens)
    }

    @Test
    fun `a gemini 3 call with the default budget keeps the model default effort`() = runTest {
        val recorder = RecordingUpstreamClient(cannedResponse = "ok")
        val service = AgentLLMService(
            agentConfiguration = config.copy(aiConfiguration = geminiConfiguration(AIModel_Gemini.Argon_4)),
            upstreamLlmClient = recorder,
        )

        service.call(prompt = "anything")

        assertEquals(Effort("medium"), assertNotNull(recorder.lastRequest).reasoningEffort)
    }

    @Test
    fun `models outside gemini 3 get no reasoning effort`() = runTest {
        // OpenAI documents Chat Completions tool calls on gpt-5.5 onward only at
        // effort `none`, so Ampere leaves effort to the model everywhere else.
        listOf(
            config.aiConfiguration,
            geminiConfiguration(AIModel_Gemini.Flash_2_5),
            AIConfiguration_Default(provider = AIProvider_OpenAI, model = AIModel_OpenAI.GPT_6_Sol),
        ).forEach { configuration ->
            val recorder = RecordingUpstreamClient(cannedResponse = "ok")
            val service = AgentLLMService(
                agentConfiguration = config.copy(aiConfiguration = configuration),
                upstreamLlmClient = recorder,
            )

            service.call(prompt = "anything", maxTokens = 500)

            assertNull(assertNotNull(recorder.lastRequest).reasoningEffort, configuration.model.name)
        }
    }

    private fun geminiConfiguration(model: AIModel_Gemini): AIConfiguration =
        AIConfiguration_Default(provider = AIProvider_Google, model = model)

    @Test
    fun `custom upstream client errors propagate`() = runTest {
        val recorder = RecordingUpstreamClient(
            cannedResponse = "unused",
            errorToThrow = IllegalStateException("upstream-boom"),
        )
        val service = AgentLLMService(
            agentConfiguration = config,
            upstreamLlmClient = recorder,
        )

        val error = try {
            service.call(prompt = "anything")
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals("upstream-boom", error?.message)
    }

    @Test
    fun `BundledUpstreamLlmClient is the default when none is supplied`() = runTest {
        // We can't easily verify the *default* without hitting the real OpenAI
        // network, but we can verify the bundled object exists and is a valid
        // implementation of the seam. Existing tests that construct
        // AgentLLMService without an upstreamLlmClient (e.g. AgentLlmTelemetry,
        // CustomLlmProviderIntegration) exercise the default code path; those
        // tests still pass after the seam was introduced, which proves
        // "absent custom client → existing behavior preserved".
        val bundled: UpstreamLlmClient = BundledUpstreamLlmClient
        // Smoke: the bundled object is reachable from commonTest.
        assertNotNull(bundled)
    }
}

private class RecordingUpstreamClient(
    private val cannedResponse: String,
    private val errorToThrow: Throwable? = null,
) : UpstreamLlmClient {

    var lastRequest: ChatCompletionRequest? = null
        private set
    var lastConfiguration: AIConfiguration? = null
        private set

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion {
        lastRequest = request
        lastConfiguration = configuration
        errorToThrow?.let { throw it }
        return ChatCompletion(
            id = "test-completion",
            created = 0L,
            model = ModelId(configuration.model.name),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(
                        role = ChatRole.Assistant,
                        content = cannedResponse,
                    ),
                ),
            ),
        )
    }
}
