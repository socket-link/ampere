package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptor
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.domain.ai.provider.OnDeviceProviderHasNoClientException

class DispatchingUpstreamLlmClientTest {

    // Anthropic stands in for a free, device-gated local provider.
    private val localConfig = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

    // Google stands in for a metered cloud provider.
    private val cloudConfig = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5)

    // OpenAI stands in for a gated-but-metered provider (availability flag alone routes local).
    private val gatedConfig = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_4_1)

    private fun registry() = InMemoryModelDescriptorRegistry(
        seed = listOf(
            descriptor(AIModel_Claude.Sonnet_5.name, AIProvider_Anthropic.id, cost = CostPolicy.Free, gated = true),
            descriptor(AIModel_Gemini.Flash_2_5.name, AIProvider_Google.id, cost = CostPolicy.Metered, gated = false),
            descriptor(AIModel_OpenAI.GPT_4_1.name, AIProvider_OpenAI.id, cost = CostPolicy.Metered, gated = true),
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
    fun `refuses a local-designated model when no engine is bound`() = runTest {
        // AMPR-371: this used to hand the call to the bundled transport, which for the
        // real on-device provider meant api.openai.com with an empty bearer token.
        val bundled = RecordingClient("cloud-answer")
        val client = DispatchingUpstreamLlmClient(registry(), localEngine = null, bundled = bundled)

        val refusal = assertFailsWith<LocalEngineNotBoundException> {
            client.call(request(), localConfig)
        }

        assertEquals(AIProvider_Anthropic.id, refusal.providerId)
        assertEquals(AIModel_Claude.Sonnet_5.name, refusal.modelId)
        assertEquals(0, bundled.callCount, "a local-designated configuration must never reach the cloud transport")
    }

    @Test
    fun `refuses a gated metered model when no engine is bound`() = runTest {
        val bundled = RecordingClient("cloud-answer")
        val client = DispatchingUpstreamLlmClient(registry(), localEngine = null, bundled = bundled)

        assertFailsWith<LocalEngineNotBoundException> { client.call(request(), gatedConfig) }

        assertEquals(0, bundled.callCount)
    }

    @Test
    fun `still routes a cloud model to bundled when no engine is bound`() = runTest {
        val bundled = RecordingClient("cloud-answer")
        val client = DispatchingUpstreamLlmClient(registry(), localEngine = null, bundled = bundled)

        val response = client.call(request(), cloudConfig)

        assertEquals("cloud-answer", response.choices.single().message.content)
        assertEquals(1, bundled.callCount)
    }

    @Test
    fun `refuses the real on-device configuration when no engine is bound`() = runTest {
        // The default catalog designates the Apple on-device model local; with no
        // engine on this host the prompt must not go anywhere.
        val bundled = RecordingClient("cloud-answer")
        val client = DispatchingUpstreamLlmClient(
            InMemoryModelDescriptorRegistry(),
            localEngine = null,
            bundled = bundled,
        )
        val onDevice = AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels)

        val refusal = assertFailsWith<LocalEngineNotBoundException> { client.call(request(), onDevice) }

        assertEquals(AIProvider_OnDevice.id, refusal.providerId)
        assertEquals(0, bundled.callCount)
    }

    @Test
    fun `the on-device configuration cannot egress through the bundled transport either`() = runTest {
        // Second line of defence: even a dispatcher whose catalog knows nothing about
        // the on-device model (so it hands the call to BundledUpstreamLlmClient) fails
        // before a request is built, because the provider has no client to send with.
        val client = DispatchingUpstreamLlmClient(
            InMemoryModelDescriptorRegistry(seed = emptyList()),
            localEngine = null,
            bundled = BundledUpstreamLlmClient,
        )
        val onDevice = AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels)

        assertFailsWith<OnDeviceProviderHasNoClientException> { client.call(request(), onDevice) }
    }

    @Test
    fun `routes to bundled when the provider has no descriptor`() = runTest {
        val engine = FakeLocalInferenceEngine()
        val bundled = RecordingClient("cloud-answer")
        // Empty registry: descriptorFor() returns null for everything.
        val client = DispatchingUpstreamLlmClient(
            InMemoryModelDescriptorRegistry(seed = emptyList()),
            engine,
            bundled,
        )

        client.call(request(), localConfig)

        assertEquals(0, engine.generateCount)
        assertEquals(1, bundled.callCount)
    }

    @Test
    fun `reports a local model as on-device only while an engine is bound`() = runTest {
        val bound = DispatchingUpstreamLlmClient(registry(), FakeLocalInferenceEngine(), RecordingClient("cloud"))
        val unbound = DispatchingUpstreamLlmClient(registry(), localEngine = null, bundled = RecordingClient("cloud"))

        assertEquals(
            InferenceLocality.ON_DEVICE,
            bound.localityOf(AIProvider_Anthropic.id, AIModel_Claude.Sonnet_5.name),
        )
        // The catalog still calls the model local. With nothing to run it on the call
        // is refused (AMPR-371); nothing about it is provably on the device, so the
        // label stays CLOUD rather than claim an execution that cannot happen.
        assertEquals(
            InferenceLocality.CLOUD,
            unbound.localityOf(AIProvider_Anthropic.id, AIModel_Claude.Sonnet_5.name),
        )
    }

    @Test
    fun `reports a metered model and an undescribed model as cloud`() = runTest {
        val client = DispatchingUpstreamLlmClient(registry(), FakeLocalInferenceEngine(), RecordingClient("cloud"))

        assertEquals(
            InferenceLocality.CLOUD,
            client.localityOf(AIProvider_Google.id, AIModel_Gemini.Flash_2_5.name),
        )
        assertEquals(
            InferenceLocality.CLOUD,
            client.localityOf(AIProvider_Google.id, "a-model-with-no-descriptor"),
        )
    }

    @Test
    fun `the reported locality matches where the call was dispatched`() = runTest {
        val engine = FakeLocalInferenceEngine(respond = { Result.success("local-answer") })
        val bundled = RecordingClient("cloud-answer")
        val client = DispatchingUpstreamLlmClient(registry(), engine, bundled)

        for (configuration in listOf(localConfig, gatedConfig, cloudConfig)) {
            val engineCallsBefore = engine.generateCount
            client.call(request(), configuration)
            val ranOnEngine = engine.generateCount > engineCallsBefore

            val reported = client.localityOf(configuration.provider.id, configuration.model.name)

            assertEquals(
                if (ranOnEngine) InferenceLocality.ON_DEVICE else InferenceLocality.CLOUD,
                reported,
                "locality reported for ${configuration.model.name} disagrees with where it ran",
            )
        }
    }

    private fun descriptor(
        modelName: String,
        providerId: String,
        cost: CostPolicy,
        gated: Boolean,
    ) = ModelDescriptor(
        modelName = modelName,
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
