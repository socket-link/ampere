package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalGenerationRequest
import link.socket.ampere.agents.domain.routing.local.LocalInferenceEngine
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

class LocalUpstreamLlmClientTest {

    private val configuration = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_5,
    )

    private val system = ChatMessage(role = ChatRole.System, content = "You are a calculator.")
    private val user = ChatMessage(role = ChatRole.User, content = "What is 2 + 2?")

    private fun request(
        messages: List<ChatMessage> = listOf(system, user),
        maxTokens: Int? = null,
        maxCompletionTokens: Int? = null,
        temperature: Double? = null,
    ): ChatCompletionRequest = ChatCompletionRequest(
        model = ModelId(AIModel_Claude.Sonnet_5.name),
        messages = messages,
        temperature = temperature,
        maxTokens = maxTokens,
        maxCompletionTokens = maxCompletionTokens,
    )

    @Test
    fun `flattens request messages to a role-labelled prompt and wraps engine text`() = runTest {
        val engine = FakeLocalInferenceEngine(
            respond = { Result.success("four") },
        )
        val client = LocalUpstreamLlmClient(engine)

        val completion = client.call(request(), configuration)

        // Engine output is surfaced as the single assistant choice.
        assertEquals("four", completion.choices.single().message.content)
        assertEquals(ChatRole.Assistant, completion.choices.single().message.role)
        assertEquals(LocalUpstreamLlmClient.LOCAL_COMPLETION_ID, completion.id)

        // The request was flattened to a System:/User: prompt.
        val prompt = assertNotNull(engine.lastPrompt)
        assertContains(prompt, "System: You are a calculator.")
        assertContains(prompt, "User: What is 2 + 2?")
        assertTrue(prompt.indexOf("System:") < prompt.indexOf("User:"))
    }

    @Test
    fun `local generation has no token usage`() = runTest {
        val client = LocalUpstreamLlmClient(FakeLocalInferenceEngine())

        val completion = client.call(request(), configuration)

        // No billing meaning for a local call; 0W is anchored by CostPolicy.Free.
        assertEquals(null, completion.usage)
    }

    @Test
    fun `engine failure surfaces as LocalInferenceException without silent fallback`() = runTest {
        val boom = IllegalStateException("model-not-loaded")
        val engine = FakeLocalInferenceEngine(
            respond = { Result.failure(boom) },
        )
        val client = LocalUpstreamLlmClient(engine)

        val thrown = try {
            client.call(request(), configuration)
            null
        } catch (e: LocalInferenceException) {
            e
        }

        val error = assertNotNull(thrown, "Local failure must surface, not silently fall back")
        assertEquals(boom, error.cause)
        assertContains(error.message ?: "", AIProvider_Anthropic.id)
    }

    // The engine request (AMPR-462).

    @Test
    fun `an engine that implements only the prompt form receives the prompt it was always sent`() = runTest {
        val engine = PromptOnlyEngine()
        val client = LocalUpstreamLlmClient(engine)

        client.call(request(maxTokens = 64, temperature = 0.3), configuration)
        client.call(request(messages = listOf(user)), configuration)

        // Literals, not the helper that writes them: these are the bytes the client sent
        // before engines were handed a request, and neither the budget nor the temperature
        // has a place in them.
        assertEquals(
            listOf(
                "System: You are a calculator.\n\nUser: What is 2 + 2?",
                "User: What is 2 + 2?",
            ),
            engine.prompts,
        )
    }

    @Test
    fun `the system message reaches the engine as instructions and the user message as the prompt`() = runTest {
        val engine = FakeLocalInferenceEngine()

        LocalUpstreamLlmClient(engine).call(request(maxTokens = 64, temperature = 0.3), configuration)

        assertEquals(
            LocalGenerationRequest(
                instructions = "You are a calculator.",
                prompt = "What is 2 + 2?",
                maxOutputTokens = 64,
                temperature = 0.3,
            ),
            engine.lastRequest,
        )
    }

    @Test
    fun `a request that set no budget and no temperature reaches the engine with neither`() = runTest {
        val engine = FakeLocalInferenceEngine()

        LocalUpstreamLlmClient(engine).call(request(), configuration)

        val sent = assertNotNull(engine.lastRequest)
        assertNull(sent.maxOutputTokens)
        assertNull(sent.temperature, "a temperature the request did not carry must not be invented")
    }

    @Test
    fun `a lone user message reaches the engine with no instructions`() = runTest {
        val engine = FakeLocalInferenceEngine()

        LocalUpstreamLlmClient(engine).call(request(messages = listOf(user)), configuration)

        assertEquals(LocalGenerationRequest(instructions = null, prompt = "What is 2 + 2?"), engine.lastRequest)
    }

    @Test
    fun `the tighter of max_tokens and max_completion_tokens is the budget`() = runTest {
        val engine = FakeLocalInferenceEngine()
        val client = LocalUpstreamLlmClient(engine)

        client.call(request(maxTokens = 64, maxCompletionTokens = 32), configuration)
        assertEquals(32, engine.lastRequest?.maxOutputTokens)

        client.call(request(maxCompletionTokens = 48), configuration)
        assertEquals(48, engine.lastRequest?.maxOutputTokens)
    }

    @Test
    fun `a conversation is not split and reaches the prompt form flattened as before`() = runTest {
        val engine = FakeLocalInferenceEngine()
        val conversation = listOf(
            system,
            user,
            ChatMessage(role = ChatRole.Assistant, content = "4"),
            ChatMessage(role = ChatRole.User, content = "And 3 + 3?"),
        )

        LocalUpstreamLlmClient(engine).call(request(messages = conversation, maxTokens = 64), configuration)

        assertNull(engine.lastRequest, "a conversation has no one system message and user turn to split into")
        assertEquals(
            "System: You are a calculator.\n\nUser: What is 2 + 2?\n\nAssistant: 4\n\nUser: And 3 + 3?",
            engine.lastPrompt,
        )
    }

    @Test
    fun `the request a caller sizes is the request the engine is handed`() = runTest {
        val engine = FakeLocalInferenceEngine()
        val messages = listOf(system, user)

        LocalUpstreamLlmClient(engine).call(request(messages, maxTokens = 64, temperature = 0.3), configuration)

        // The count reads neither the budget nor the temperature, so the sizing request carries neither.
        val sent = assertNotNull(engine.lastRequest)
        assertEquals(
            sent.copy(maxOutputTokens = null, temperature = null),
            LocalUpstreamLlmClient.generationRequestFor(messages),
        )
        val conversation = messages + ChatMessage(role = ChatRole.Assistant, content = "4")
        assertNull(
            LocalUpstreamLlmClient.generationRequestFor(conversation),
            "a conversation splits into no request, and is sized as its flattened prompt",
        )
    }

    @Test
    fun `the binding hands its engine the request whole`() = runTest {
        val engine = FakeLocalInferenceEngine()
        val binding = OnDeviceInferenceBinding(engine = engine)

        binding.client.callDetailed(request(maxTokens = 2_048, temperature = 0.3), binding.onDeviceConfiguration)

        assertEquals(
            LocalGenerationRequest(
                instructions = "You are a calculator.",
                prompt = "What is 2 + 2?",
                maxOutputTokens = 2_048,
                temperature = 0.3,
            ),
            engine.lastRequest,
            "an observed engine that flattened the request would leave the bound engine only a prompt",
        )
    }

    /** An engine written before the request form existed: the prompt form and nothing else. */
    private class PromptOnlyEngine : LocalInferenceEngine {
        val prompts = mutableListOf<String>()

        override suspend fun probe(): LocalCapacity = LocalCapacity(available = true)

        override suspend fun generate(prompt: String): Result<String> {
            prompts += prompt
            return Result.success("4")
        }
    }
}
