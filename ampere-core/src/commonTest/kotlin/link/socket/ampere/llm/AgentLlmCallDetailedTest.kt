package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
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
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
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
 * [AgentLLMService.callDetailed] (AMPR-327): the text [AgentLLMService.call]
 * returns, plus which model produced it and why that one.
 */
class AgentLlmCallDetailedTest {

    private val cloudConfig = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5)
    private val onDeviceConfig = AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels)

    @Test
    fun `names the model of the agent when no relay resolves the call`() = runTest {
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = cloudConfig,
                upstreamLlmClient = CannedClient("from the model of the agent"),
            ),
        )

        val result = service.callDetailed(prompt = "Hello")

        assertEquals("from the model of the agent", result.text)
        assertEquals(AIProvider_Google.id, result.providerId)
        assertEquals(AIModel_Gemini.Flash_2_5.name, result.modelId)
        assertEquals("agent_configuration", result.routingReason)
        assertTrue(result.latencyMs >= 0)
    }

    @Test
    fun `names the model the relay chose rather than the fallback of the agent`() = runTest {
        val registry = InMemoryModelDescriptorRegistry()
        val engine = FakeLocalInferenceEngine(respond = { Result.success("from the device") })
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = OnDeviceAssistantAgent,
                // The fallback is a cloud model on purpose: the result must name what ran.
                aiConfiguration = cloudConfig,
                cognitiveRelay = CognitiveRelayImpl(
                    initialConfig = RelayConfig(rules = CapabilityRoutingDefaults.defaultCapabilityRules()),
                    registry = registry,
                ),
                upstreamLlmClient = DispatchingUpstreamLlmClient(registry, engine, CannedClient("from the cloud")),
            ),
        )

        val result = service.callDetailed(
            prompt = "Hello",
            routingContext = RoutingContext(
                requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
                localCapacity = LocalCapacity(available = true, providerId = AIProvider_OnDevice.id),
            ),
        )

        assertEquals("from the device", result.text)
        assertEquals(onDeviceConfig.provider.id, result.providerId)
        assertEquals(onDeviceConfig.model.name, result.modelId)
        assertEquals("capability:${AIProvider_OnDevice.id}", result.routingReason)
    }

    @Test
    fun `names a custom provider as the route when one short-circuits the call`() = runTest {
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = cloudConfig,
                llmProvider = { "from the custom provider" },
            ),
        )

        val result = service.callDetailed(prompt = "Hello")

        assertEquals("from the custom provider", result.text)
        assertEquals(AgentLLMService.CUSTOM_PROVIDER_ROUTING_REASON, result.routingReason)
        assertEquals(AIModel_Gemini.Flash_2_5.name, result.modelId)
    }

    @Test
    fun `call returns exactly the text of the detailed result`() = runTest {
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = cloudConfig,
                upstreamLlmClient = CannedClient("the same text"),
            ),
        )

        assertEquals(service.callDetailed(prompt = "Hello").text, service.call(prompt = "Hello"))
    }

    private class CannedClient(private val response: String) : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = ChatCompletion(
            id = "canned",
            created = 0L,
            model = ModelId(configuration.model.name),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = ChatRole.Assistant, content = response),
                ),
            ),
        )
    }
}
