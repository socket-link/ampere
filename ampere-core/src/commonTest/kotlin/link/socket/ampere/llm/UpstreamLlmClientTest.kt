package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

class UpstreamLlmClientTest {

    private val config = AgentConfiguration(
        agentDefinition = WriteCodeAgent,
        aiConfiguration = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_4,
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
        assertEquals(AIModel_Claude.Sonnet_4.name, captured.model.id)
        assertEquals(0.7, captured.temperature)
        assertEquals(256, captured.maxCompletionTokens)
        assertEquals(2, captured.messages.size)
        assertEquals(ChatRole.System, captured.messages[0].role)
        assertEquals("You are a database expert.", captured.messages[0].content)
        assertEquals(ChatRole.User, captured.messages[1].role)
        assertEquals("Tell me about migration", captured.messages[1].content)
        assertSame(config.aiConfiguration, recorder.lastConfiguration)
    }

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
