package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.InMemoryProviderDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptor
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class DispatchingUpstreamLlmClientTest {

    // Anthropic stands in for a free, device-gated local provider.
    private val localConfig = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_4)

    // Google stands in for a metered cloud provider.
    private val cloudConfig = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5)

    // OpenAI stands in for a gated-but-metered provider (availability flag alone routes local).
    private val gatedConfig = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_4_1)

    private fun registry() = InMemoryProviderDescriptorRegistry(
        seed = listOf(
            descriptor(AIProvider_Anthropic.id, cost = CostPolicy.Free, gated = true),
            descriptor(AIProvider_Google.id, cost = CostPolicy.Metered, gated = false),
            descriptor(AIProvider_OpenAI.id, cost = CostPolicy.Metered, gated = true),
        ),
    )

    @Test
    fun `routes free local provider to the local engine`() = runTest {
        val engine = FakeLocalInferenceEngine(respond = { Result.success("local-answer") })
        val bundled = RecordingClient("cloud-answer")
        val client = DispatchingUpstreamLlmClient(registry(), engine, bundled)

        val response = client.call(request(), localConfig)

        assertEquals("local-answer", response.choices.single().message.content)
        assertEquals(1, engine.generateCount)
        assertEquals(0, bundled.callCount, "Free local provider must not touch the cloud path")
    }

    @Test
    fun `routes availability-gated provider to the local engine even when metered`() = runTest {
        val engine = FakeLocalInferenceEngine(respond = { Result.success("local-answer") })
        val bundled = RecordingClient("cloud-answer")
        val client = DispatchingUpstreamLlmClient(registry(), engine, bundled)

        val response = client.call(request(), gatedConfig)

        assertEquals("local-answer", response.choices.single().message.content)
        assertEquals(1, engine.generateCount)
        assertEquals(0, bundled.callCount)
    }

    @Test
    fun `routes metered cloud provider to the bundled client`() = runTest {
        val engine = FakeLocalInferenceEngine()
        val bundled = RecordingClient("cloud-answer")
        val client = DispatchingUpstreamLlmClient(registry(), engine, bundled)

        val response = client.call(request(), cloudConfig)

        assertEquals("cloud-answer", response.choices.single().message.content)
        assertEquals(0, engine.generateCount)
        assertEquals(1, bundled.callCount)
    }

    @Test
    fun `routes to bundled when no engine is bound even for a free provider`() = runTest {
        val bundled = RecordingClient("cloud-answer")
        val client = DispatchingUpstreamLlmClient(registry(), localEngine = null, bundled = bundled)

        val response = client.call(request(), localConfig)

        assertEquals("cloud-answer", response.choices.single().message.content)
        assertEquals(1, bundled.callCount)
    }

    @Test
    fun `routes to bundled when the provider has no descriptor`() = runTest {
        val engine = FakeLocalInferenceEngine()
        val bundled = RecordingClient("cloud-answer")
        // Empty registry: descriptorFor() returns null for everything.
        val client = DispatchingUpstreamLlmClient(
            InMemoryProviderDescriptorRegistry(seed = emptyList()),
            engine,
            bundled,
        )

        client.call(request(), localConfig)

        assertEquals(0, engine.generateCount)
        assertEquals(1, bundled.callCount)
    }

    private fun descriptor(
        providerId: String,
        cost: CostPolicy,
        gated: Boolean,
    ) = ProviderDescriptor(
        providerId = providerId,
        capabilities = emptySet(),
        reasoning = RelativeReasoning.LOW,
        maxContextTokens = 8_192,
        supportedInputs = SupportedInputs.TEXT,
        cost = cost,
        availabilityGated = gated,
    )

    private fun request(): ChatCompletionRequest = ChatCompletionRequest(
        model = ModelId("test-model"),
        messages = listOf(ChatMessage(role = ChatRole.User, content = "hello")),
    )

    private class RecordingClient(
        private val cannedResponse: String,
    ) : UpstreamLlmClient {
        var callCount: Int = 0
            private set

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            callCount++
            return ChatCompletion(
                id = "cloud",
                created = 0L,
                model = ModelId(configuration.model.name),
                choices = listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(role = ChatRole.Assistant, content = cannedResponse),
                    ),
                ),
            )
        }
    }
}
