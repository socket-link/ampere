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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.CapabilityRoutingDefaults
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingFloorUnmetException
import link.socket.ampere.agents.domain.routing.RoutingRule
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.domain.routing.local.LocalGenerationRequest
import link.socket.ampere.domain.agent.bundled.APIDesignAgent
import link.socket.ampere.domain.agent.bundled.OnDeviceAssistantAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

/**
 * The context-window fit sized by the engine's own tokenizer (AMPR-460).
 *
 * `AgentLLMService` decides whether a prompt fits the on-device window from a
 * token figure. With an engine that can count, that figure is the engine's count
 * of the request it would be handed (AMPR-462): what it will send, for an engine
 * that counts requests, and the flattened prompt for one that counts only text.
 * With one that cannot, it is `PromptTokenEstimator`'s four characters per token,
 * as before. The fit's rule is unchanged either way: it widens an existing
 * requirement and never creates one.
 *
 * Driven through the service rather than `OnDeviceInferenceSession`, which needs
 * a door that commonTest has no driver for; the session's own sizing is pinned
 * in `OnDeviceInferenceSessionTest` (jvmTest).
 */
class OnDeviceTokenCountFitTest {

    private val cloudConfig = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5)
    private val onDeviceConfig = AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels)

    // The dispatcher's seam.

    @Test
    fun `the dispatcher reports no count when no engine is bound`() = runTest {
        val client = DispatchingUpstreamLlmClient(InMemoryModelDescriptorRegistry(), localEngine = null)

        assertNull(client.countLocalTokens("anything"))
    }

    @Test
    fun `the dispatcher reports no count when the engine cannot count`() = runTest {
        val client = DispatchingUpstreamLlmClient(InMemoryModelDescriptorRegistry(), FakeLocalInferenceEngine())

        assertNull(client.countLocalTokens("anything"))
    }

    @Test
    fun `the dispatcher reports the engine count when it has one`() = runTest {
        val engine = FakeLocalInferenceEngine(countTokens = { it.length * 2 })
        val client = DispatchingUpstreamLlmClient(InMemoryModelDescriptorRegistry(), engine)

        assertEquals(14, client.countLocalTokens("seven c"))
        assertEquals("seven c", engine.lastCountedText)
    }

    @Test
    fun `an engine that throws while counting is one that cannot count`() = runTest {
        val engine = FakeLocalInferenceEngine(countTokens = { throw IllegalStateException("tokenizer offline") })
        val client = DispatchingUpstreamLlmClient(InMemoryModelDescriptorRegistry(), engine)

        assertNull(client.countLocalTokens("anything"))
    }

    @Test
    fun `the binding forwards the engine count through its observed engine`() = runTest {
        val engine = FakeLocalInferenceEngine(capacity = available(), countTokens = { 77 })
        val binding = OnDeviceInferenceBinding(engine = engine)

        assertEquals(77, binding.client.countLocalTokens("anything"))

        val throwing = FakeLocalInferenceEngine(capacity = available(), countTokens = { error("tokenizer offline") })
        assertNull(OnDeviceInferenceBinding(engine = throwing).client.countLocalTokens("anything"))
    }

    @Test
    fun `the dispatcher reports no request count when no engine is bound`() = runTest {
        val client = DispatchingUpstreamLlmClient(InMemoryModelDescriptorRegistry(), localEngine = null)

        assertNull(client.countLocalTokens(LocalGenerationRequest(instructions = null, prompt = "anything")))
    }

    @Test
    fun `the binding forwards a request count through its observed engine`() = runTest {
        val request = LocalGenerationRequest(instructions = "Be brief.", prompt = "Hello")
        val engine = FakeLocalInferenceEngine(capacity = available(), countRequests = { 77 })

        assertEquals(77, OnDeviceInferenceBinding(engine = engine).client.countLocalTokens(request))
        assertEquals(request, engine.lastCountedRequest)

        val throwing = FakeLocalInferenceEngine(capacity = available(), countRequests = { error("tokenizer offline") })
        assertNull(OnDeviceInferenceBinding(engine = throwing).client.countLocalTokens(request))
    }

    @Test
    fun `a conversation is counted as the flattened prompt it is handed`() = runTest {
        val engine = FakeLocalInferenceEngine(countTokens = { 9 }, countRequests = { 99 })
        val client = DispatchingUpstreamLlmClient(InMemoryModelDescriptorRegistry(), engine)
        val conversation = listOf(
            ChatMessage(role = ChatRole.System, content = "Be brief."),
            ChatMessage(role = ChatRole.User, content = "Hello"),
            ChatMessage(role = ChatRole.Assistant, content = "Hi"),
            ChatMessage(role = ChatRole.User, content = "Again"),
        )

        assertEquals(9, client.countLocalTokens(conversation))
        assertEquals("System: Be brief.\n\nUser: Hello\n\nAssistant: Hi\n\nUser: Again", engine.lastCountedText)
        assertNull(engine.lastCountedRequest, "a conversation splits into no request, so none is counted")
    }

    // The fit.

    @Test
    fun `a count above the estimate keeps a prompt that fits by estimate off the device and in the cloud`() = runTest {
        // "Hello" is a handful of tokens by estimate; the engine says it is the whole window.
        val engine = FakeLocalInferenceEngine(capacity = available(), countTokens = { 5_000 })
        val cloud = RecordingCloud()
        val service = service(engine, cloud, CapabilityRoutingDefaults.defaultCapabilityRules())

        val result = service.callDetailed(prompt = "Hello", maxTokens = OUTPUT_BUDGET, routingContext = eligible())

        assertNotEquals(AIProvider_OnDevice.id, result.providerId, result.routingReason)
        assertEquals(1, cloud.callCount)
        assertEquals(0, engine.generateCount, "a prompt the engine counted over its window must not reach it")
    }

    @Test
    fun `a count above the estimate refuses a prompt that fits by estimate when no cloud is bound`() = runTest {
        val engine = FakeLocalInferenceEngine(capacity = available(), countTokens = { 5_000 })
        val service = service(engine, cloud = null, CapabilityRoutingDefaults.onDeviceCapabilityRules())

        assertFailsWith<RoutingFloorUnmetException> {
            service.callDetailed(prompt = "Hello", maxTokens = OUTPUT_BUDGET, routingContext = eligible())
        }
        assertEquals(0, engine.generateCount)
    }

    @Test
    fun `the engine counts exactly the flattened prompt it would be handed`() = runTest {
        val engine = FakeLocalInferenceEngine(capacity = available(), countTokens = { 10 })
        val service = service(engine, cloud = null, CapabilityRoutingDefaults.onDeviceCapabilityRules())

        service.callDetailed(
            prompt = "Hello",
            systemMessage = "Be brief.",
            maxTokens = OUTPUT_BUDGET,
            routingContext = eligible(),
        )

        assertEquals("System: Be brief.\n\nUser: Hello", engine.lastCountedText)
        assertEquals(engine.lastCountedText, engine.lastPrompt, "what was counted is what was generated from")
    }

    @Test
    fun `the fit counts the request the engine is handed`() = runTest {
        val engine = FakeLocalInferenceEngine(capacity = available(), countRequests = { 10 })
        val service = service(engine, cloud = null, CapabilityRoutingDefaults.onDeviceCapabilityRules())

        service.callDetailed(
            prompt = "Hello",
            systemMessage = "Be brief.",
            maxTokens = OUTPUT_BUDGET,
            routingContext = eligible(),
        )

        val counted = assertNotNull(engine.lastCountedRequest)
        val sent = assertNotNull(engine.lastRequest)
        assertEquals(LocalGenerationRequest(instructions = "Be brief.", prompt = "Hello"), counted)
        assertEquals(
            counted,
            sent.copy(maxOutputTokens = null, temperature = null),
            "what was counted is what was sent",
        )
    }

    @Test
    fun `a request count over the window keeps the call off the device though its flattened string fits`() = runTest {
        // The undercount AMPR-462 closed: a string count misses the turns an engine wraps
        // around every call, and the request count is what the model is billed.
        val engine = FakeLocalInferenceEngine(capacity = available(), countTokens = { 10 }, countRequests = { 5_000 })
        val cloud = RecordingCloud()
        val service = service(engine, cloud, CapabilityRoutingDefaults.defaultCapabilityRules())

        val result = service.callDetailed(prompt = "Hello", maxTokens = OUTPUT_BUDGET, routingContext = eligible())

        assertNotEquals(AIProvider_OnDevice.id, result.providerId, result.routingReason)
        assertEquals(1, cloud.callCount)
        assertEquals(0, engine.generateCount, "a request the engine counted over its window must not reach it")
        assertEquals(0, engine.tokenCountCount, "an engine that counts requests is not asked for a string")
    }

    @Test
    fun `a count below the estimate admits a prompt the estimate would have refused`() = runTest {
        // About 5,000 tokens by estimate, well over the 4,096 window; the engine knows better.
        val engine = FakeLocalInferenceEngine(capacity = available(), countTokens = { 1_000 })
        val cloud = RecordingCloud()
        val service = service(engine, cloud, CapabilityRoutingDefaults.defaultCapabilityRules())

        val result = service.callDetailed(
            prompt = OVERSIZED_PROMPT,
            maxTokens = OUTPUT_BUDGET,
            routingContext = eligible(),
        )

        assertEquals(AIProvider_OnDevice.id, result.providerId, result.routingReason)
        assertEquals(1, engine.generateCount)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `an engine reporting no count leaves routing by the estimate exactly as it was`() = runTest {
        val engine = FakeLocalInferenceEngine(capacity = available())
        val cloud = RecordingCloud()
        val service = service(engine, cloud, CapabilityRoutingDefaults.defaultCapabilityRules())

        val short = service.callDetailed(prompt = "Hello", maxTokens = OUTPUT_BUDGET, routingContext = eligible())
        assertEquals(AIProvider_OnDevice.id, short.providerId, short.routingReason)

        val long = service.callDetailed(
            prompt = OVERSIZED_PROMPT,
            maxTokens = OUTPUT_BUDGET,
            routingContext = eligible(),
        )
        assertNotEquals(AIProvider_OnDevice.id, long.providerId, long.routingReason)

        assertEquals(1, engine.generateCount)
        assertEquals(1, cloud.callCount)
    }

    @Test
    fun `an engine that throws while counting falls back to the estimate and the call succeeds`() = runTest {
        val engine = FakeLocalInferenceEngine(
            capacity = available(),
            countTokens = { throw IllegalStateException("tokenizer offline") },
        )
        val service = service(engine, cloud = null, CapabilityRoutingDefaults.onDeviceCapabilityRules())

        val result = service.callDetailed(prompt = "Hello", maxTokens = OUTPUT_BUDGET, routingContext = eligible())

        assertEquals(AIProvider_OnDevice.id, result.providerId, result.routingReason)
        assertEquals(1, engine.generateCount)
    }

    @Test
    fun `a call with no requirement is neither counted nor sized`() = runTest {
        val engine = FakeLocalInferenceEngine(capacity = available(), countTokens = { 5_000 })
        val cloud = RecordingCloud()
        val registry = InMemoryModelDescriptorRegistry()
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                // An agent with no floor of its own, so nothing creates a requirement.
                agentDefinition = APIDesignAgent,
                aiConfiguration = cloudConfig,
                cognitiveRelay = CognitiveRelayImpl(
                    initialConfig = RelayConfig(rules = CapabilityRoutingDefaults.defaultCapabilityRules()),
                    registry = registry,
                ),
                upstreamLlmClient = DispatchingUpstreamLlmClient(registry, engine, cloud),
            ),
        )

        // A context with no requirement: the agent's configuration answers, as before.
        service.callDetailed(prompt = "Hello", maxTokens = OUTPUT_BUDGET, routingContext = RoutingContext())

        assertEquals(0, engine.tokenCountCount, "sizing a call nothing declared eligible would make it eligible")
        assertEquals(1, cloud.callCount)
    }

    // Fixtures.

    private fun service(
        engine: FakeLocalInferenceEngine,
        cloud: UpstreamLlmClient?,
        rules: List<RoutingRule>,
    ): AgentLLMService {
        val registry = InMemoryModelDescriptorRegistry()
        return AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = OnDeviceAssistantAgent,
                // The fallback is what the relay validates against the whole requirement when no
                // rule matches: on-device only, it is the device, so a prompt that does not fit
                // is a floor unmet rather than a quiet hop to the cloud configuration.
                aiConfiguration = if (cloud == null) onDeviceConfig else cloudConfig,
                cognitiveRelay = CognitiveRelayImpl(initialConfig = RelayConfig(rules = rules), registry = registry),
                upstreamLlmClient = DispatchingUpstreamLlmClient(registry, engine, cloud ?: RefusingCloud),
            ),
        )
    }

    private fun eligible() = RoutingContext(requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT))

    private fun available() = LocalCapacity(
        available = true,
        modelId = AIModel_OnDevice.AppleFoundationModels.name,
        maxContextTokens = 4_096,
        providerId = AIProvider_OnDevice.id,
    )

    private class RecordingCloud : UpstreamLlmClient {
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
                        message = ChatMessage(role = ChatRole.Assistant, content = "from the cloud"),
                    ),
                ),
            )
        }
    }

    private object RefusingCloud : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = throw MissingUpstreamLlmClientException(agentName = null)
    }

    private companion object {
        /** Half the window, as `OnDeviceInferenceSession` reserves. */
        const val OUTPUT_BUDGET = 2_048

        /** About 5,000 tokens by the estimator's four-characters-per-token rule. */
        val OVERSIZED_PROMPT: String = "word ".repeat(4_000)
    }
}
