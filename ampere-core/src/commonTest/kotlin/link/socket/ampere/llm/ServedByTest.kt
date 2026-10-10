package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.CapabilityRoutingDefaults
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.domain.agent.bundled.OnDeviceAssistantAgent
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

/**
 * The served-provider seam (AMPR-391): an [UpstreamLlmClient] can report what
 * actually answered, and [AgentLLMService]'s result follows it.
 *
 * The two halves that matter are symmetric. A transport that reports a
 * [ServedBy] moves the record onto it; a transport that reports nothing — which
 * is every implementation written before this existed — is indistinguishable
 * from before.
 */
class ServedByTest {

    private val cloudConfig = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5)

    @Test
    fun `the default callDetailed wraps call and reports nothing`() = runTest {
        val legacy = CallOnlyClient("from the only method it implements")

        val upstream = legacy.callDetailed(request(), cloudConfig)

        assertEquals("from the only method it implements", upstream.completion.choices.single().message.content)
        assertNull(upstream.served, "a transport that does not override callDetailed must attribute nothing")
    }

    @Test
    fun `a transport that reports nothing leaves the call named by the resolved configuration`() = runTest {
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = cloudConfig,
                upstreamLlmClient = CallOnlyClient("answer"),
            ),
        )

        val result = service.callDetailed(prompt = "Hello")

        assertEquals("answer", result.text)
        assertEquals(AIProvider_Google.id, result.providerId)
        assertEquals(AIModel_Gemini.Flash_2_5.name, result.modelId)
        assertEquals("agent_configuration", result.routingReason)
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun `the result names the provider and model that served the call`() = runTest {
        // The proxy was asked for Gemini Flash and routed the call to Anthropic on a
        // quality tier of its own. Ampere's result must name what answered.
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = cloudConfig,
                upstreamLlmClient = ReroutingProxy(
                    response = "from the model the proxy picked",
                    served = ServedBy(
                        providerId = "anthropic",
                        modelId = "claude-sonnet-5",
                        usage = TokenUsage(inputTokens = 310, outputTokens = 44),
                        estimatedCostUsd = 0.0021,
                        routingReason = "tier=premium",
                        latencyMs = 870,
                    ),
                ),
            ),
        )

        val result = service.callDetailed(prompt = "Hello")

        assertEquals("from the model the proxy picked", result.text)
        assertEquals("anthropic", result.providerId)
        assertEquals("claude-sonnet-5", result.modelId)
        assertEquals("tier=premium", result.routingReason)
        assertEquals(870, result.latencyMs)
    }

    @Test
    fun `a transport reporting only the ids leaves the relay reason standing`() = runTest {
        // routingReason and latencyMs are null: a proxy that can name the model but has
        // no rule vocabulary of its own must not blank out why Ampere routed the call.
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = cloudConfig,
                upstreamLlmClient = ReroutingProxy(
                    response = "answer",
                    served = ServedBy(providerId = "anthropic", modelId = "claude-sonnet-5"),
                ),
            ),
        )

        val result = service.callDetailed(prompt = "Hello")

        assertEquals("anthropic", result.providerId)
        assertEquals("claude-sonnet-5", result.modelId)
        assertEquals("agent_configuration", result.routingReason)
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun `the local client attributes the call to the on-device provider and model`() = runTest {
        val onDeviceConfig = AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels)
        val client = LocalUpstreamLlmClient(FakeLocalInferenceEngine(respond = { Result.success("on the device") }))

        val upstream = client.callDetailed(request(), onDeviceConfig)
        val served = assertServed(upstream)

        assertEquals("on the device", upstream.completion.choices.single().message.content)
        assertEquals(AIProvider_OnDevice.id, served.providerId)
        assertEquals(AIModel_OnDevice.AppleFoundationModels.name, served.modelId)
        // A local generation has no token accounting, which is the fact being reported —
        // not an absence of one. Its 0-Watt cost is anchored by the Free descriptor.
        assertNull(served.usage)
        assertNull(served.estimatedCostUsd)
        // The relay's reason says why the device was chosen; the engine adds none.
        assertNull(served.routingReason)
        // Latency is Ampere's own measurement to take for an in-process engine, and
        // taking it the same way as every cloud call is what makes the two comparable.
        assertNull(served.latencyMs)
    }

    @Test
    fun `the dispatching client forwards what the cloud transport reported`() = runTest {
        val served = ServedBy(providerId = "anthropic", modelId = "claude-sonnet-5", routingReason = "failover:2")
        val client = DispatchingUpstreamLlmClient(
            registry = InMemoryModelDescriptorRegistry(seed = emptyList()),
            localEngine = null,
            bundled = ReroutingProxy(response = "answer", served = served),
        )

        val upstream = client.callDetailed(request(), cloudConfig)

        assertEquals(served, assertServed(upstream))
    }

    @Test
    fun `the dispatching client attributes a local call to the device`() = runTest {
        val onDeviceConfig = AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels)
        val client = DispatchingUpstreamLlmClient(
            // The bundled catalog designates the Apple on-device model local.
            registry = InMemoryModelDescriptorRegistry(),
            localEngine = FakeLocalInferenceEngine(respond = { Result.success("on the device") }),
            bundled = CallOnlyClient("from the cloud"),
        )

        val served = assertServed(client.callDetailed(request(), onDeviceConfig))

        assertEquals(AIProvider_OnDevice.id, served.providerId)
        assertEquals(AIModel_OnDevice.AppleFoundationModels.name, served.modelId)
    }

    @Test
    fun `the served ids are what the locality classifier is asked about`() = runTest {
        // AMPR-327's "say where one answer came from" reads LlmCallResult, so a served
        // on-device model is classified on-device even though the agent's fallback is a
        // cloud configuration.
        val registry = InMemoryModelDescriptorRegistry()
        val client = DispatchingUpstreamLlmClient(
            registry = registry,
            localEngine = FakeLocalInferenceEngine(respond = { Result.success("on the device") }),
            bundled = CallOnlyClient("from the cloud"),
        )
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = OnDeviceAssistantAgent,
                aiConfiguration = cloudConfig,
                cognitiveRelay = CognitiveRelayImpl(
                    initialConfig = RelayConfig(rules = CapabilityRoutingDefaults.defaultCapabilityRules()),
                    registry = registry,
                ),
                upstreamLlmClient = client,
            ),
        )

        val result = service.callDetailed(
            prompt = "Hello",
            routingContext = RoutingContext(
                requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
                localCapacity = LocalCapacity(available = true, providerId = AIProvider_OnDevice.id),
            ),
        )

        assertEquals(AIProvider_OnDevice.id, result.providerId)
        assertEquals(AIModel_OnDevice.AppleFoundationModels.name, result.modelId)
        assertEquals(InferenceLocality.ON_DEVICE, client.localityOf(result.providerId, result.modelId))
    }

    private fun assertServed(upstream: UpstreamCompletion): ServedBy =
        assertNotNull(upstream.served, "expected the transport to report what served the call")

    private fun request(): ChatCompletionRequest = ChatCompletionRequest(
        model = ModelId("requested-model"),
        messages = listOf(ChatMessage(role = ChatRole.User, content = "hello")),
    )

    /** A transport written before the served seam existed: it overrides [call] and nothing else. */
    private class CallOnlyClient(private val response: String) : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = completion(response, configuration.model.name)
    }

    /** A consumer's proxy that routes server-side and says so. */
    private class ReroutingProxy(
        private val response: String,
        private val served: ServedBy,
        private val usage: Usage? = null,
    ) : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = callDetailed(request, configuration).completion

        override suspend fun callDetailed(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): UpstreamCompletion = UpstreamCompletion(
            completion = completion(response, served.modelId, usage),
            served = served,
        )
    }

    private companion object {
        fun completion(
            text: String,
            modelName: String,
            usage: Usage? = null,
        ): ChatCompletion = ChatCompletion(
            id = "served-by-test",
            created = 0L,
            model = ModelId(modelName),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = ChatRole.Assistant, content = text),
                ),
            ),
            usage = usage,
        )
    }
}
