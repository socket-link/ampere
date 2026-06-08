package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

class LocalUpstreamLlmClientTest {

    private val configuration = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4,
    )

    private fun request(): ChatCompletionRequest = ChatCompletionRequest(
        model = ModelId(AIModel_Claude.Sonnet_4.name),
        messages = listOf(
            ChatMessage(role = ChatRole.System, content = "You are a calculator."),
            ChatMessage(role = ChatRole.User, content = "What is 2 + 2?"),
        ),
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
}
