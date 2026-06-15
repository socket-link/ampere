package link.socket.ampere.llm

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingRule
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptor
import link.socket.ampere.agents.domain.routing.capability.ProviderCapability
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
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

/**
 * End-to-end integration test for AMPR-209's local execution surface.
 *
 * Mirrors [CustomLlmProviderIntegrationTest], but instead of the
 * [link.socket.ampere.domain.llm.LlmProvider] *bypass*, the local configuration
 * is selected **by the relay** ([RoutingRule.ByCapability]) and executed through
 * the observable [UpstreamLlmClient] seam via a
 * [DispatchingUpstreamLlmClient] → [LocalUpstreamLlmClient] → fake engine. This
 * is the path AMPR-203 (D1) requires: selection *and* execution stay observable.
 */
class LocalInferenceRelayIntegrationTest {

    // Anthropic stands in for the device-gated local provider: free, text-only,
    // no world knowledge.
    private val localConfig = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_4)

    // Google stands in for the cloud "grid" provider: metered, full capabilities.
    private val gridConfig = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_2_5)

    // The agent's static fallback, used only when no rule matches.
    private val agentFallback = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_4_1)

    private fun registry() = InMemoryModelDescriptorRegistry(
        seed = listOf(
            ModelDescriptor(
                modelName = AIModel_Claude.Sonnet_4.name,
                providerId = AIProvider_Anthropic.id,
                capabilities = emptySet(),
                reasoning = RelativeReasoning.LOW,
                maxContextTokens = 8_192,
                supportedInputs = SupportedInputs.TEXT,
                cost = CostPolicy.Free,
                availabilityGated = true,
            ),
            ModelDescriptor(
                modelName = AIModel_Gemini.Flash_2_5.name,
                providerId = AIProvider_Google.id,
                capabilities = setOf(
                    ProviderCapability.WORLD_KNOWLEDGE,
                    ProviderCapability.TOOL_CALLING,
                    ProviderCapability.LONG_CONTEXT,
                ),
                reasoning = RelativeReasoning.HIGH,
                maxContextTokens = 200_000,
                supportedInputs = SupportedInputs.TEXT_AND_IMAGE,
                cost = CostPolicy.Metered,
            ),
        ),
    )

    @Test
    fun `text requirement selects local provider and executes through the local engine`() = runBlocking {
        val registry = registry()
        val eventBus = EventSerialBus(CoroutineScope(Dispatchers.Default))
        val routeSelections = subscribeToRouteSelected(eventBus)

        val engine = FakeLocalInferenceEngine(respond = { Result.success(LOCAL_RESPONSE) })
        val cloud = RecordingUpstreamClient("cloud-response")

        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                // Local-first ordering: first capable provider wins.
                rules = listOf(
                    RoutingRule.ByCapability(localConfig),
                    RoutingRule.ByCapability(gridConfig),
                ),
            ),
            eventBus = eventBus,
            registry = registry,
        )

        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = agentFallback,
                cognitiveConfig = CognitiveConfig(),
                cognitiveRelay = relay,
                upstreamLlmClient = DispatchingUpstreamLlmClient(registry, engine, cloud),
            ),
        )

        val response = service.call(
            prompt = "Rewrite this sentence.",
            systemMessage = "You are a text transformer.",
            routingContext = RoutingContext(
                requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
                // The device-gated local provider's availability gate (AMPR-207)
                // must be open for the relay to select it.
                localCapacity = LocalCapacity(
                    available = true,
                    providerId = AIProvider_Anthropic.id,
                ),
            ),
        )

        // Response is the local engine's output, executed via LocalUpstreamLlmClient.
        assertEquals(LOCAL_RESPONSE, response)
        assertEquals(1, engine.generateCount, "Local engine must have executed exactly once")
        assertEquals(0, cloud.callCount, "Cloud path must not be touched for a free local route")

        // The engine saw the flattened system + user prompt (the request shape).
        assertContains(engine.lastPrompt ?: "", "System: You are a text transformer.")
        assertContains(engine.lastPrompt ?: "", "User: Rewrite this sentence.")

        // RouteSelected fired on the relay path (NOT the LlmProvider short-circuit).
        delay(EVENT_DELIVERY_MS)
        assertEquals(1, routeSelections.size, "Expected exactly one RouteSelected event")
        val decision = (routeSelections.first() as RoutingEvent.RouteSelected).decision
        assertEquals(AIProvider_Anthropic.name, decision.providerName)
        assertEquals(AIModel_Claude.Sonnet_4.name, decision.modelName)
        assertEquals("capability:${AIProvider_Anthropic.id}", decision.matchedRule)
    }

    @Test
    fun `world-knowledge requirement falls through to grid and executes on the cloud client`() = runBlocking {
        val registry = registry()
        val eventBus = EventSerialBus(CoroutineScope(Dispatchers.Default))
        val routeSelections = subscribeToRouteSelected(eventBus)

        val engine = FakeLocalInferenceEngine(respond = { Result.success(LOCAL_RESPONSE) })
        val cloud = RecordingUpstreamClient("cloud-response")

        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByCapability(localConfig),
                    RoutingRule.ByCapability(gridConfig),
                ),
            ),
            eventBus = eventBus,
            registry = registry,
        )

        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = agentFallback,
                cognitiveConfig = CognitiveConfig(),
                cognitiveRelay = relay,
                upstreamLlmClient = DispatchingUpstreamLlmClient(registry, engine, cloud),
            ),
        )

        val response = service.call(
            prompt = "Who won the 1998 World Cup?",
            systemMessage = "You answer factual questions.",
            routingContext = RoutingContext(
                requirements = CapabilityRequirement(
                    required = setOf(ProviderCapability.WORLD_KNOWLEDGE),
                    inputs = SupportedInputs.TEXT,
                ),
            ),
        )

        // The metered grid provider executes via the bundled/cloud client, not local.
        assertEquals("cloud-response", response)
        assertEquals(0, engine.generateCount, "Local engine must not run for a metered grid route")
        assertEquals(1, cloud.callCount)

        delay(EVENT_DELIVERY_MS)
        val decision = (routeSelections.first() as RoutingEvent.RouteSelected).decision
        assertEquals(AIProvider_Google.name, decision.providerName)
        assertTrue(routeSelections.isNotEmpty(), "RouteSelected must fire on the relay path")
    }

    private fun subscribeToRouteSelected(eventBus: EventSerialBus): MutableList<Event> {
        val received = mutableListOf<Event>()
        eventBus.subscribe(
            agentId = "route-subscriber",
            eventType = RoutingEvent.RouteSelected.EVENT_TYPE,
            handler = EventHandler { event, _ -> received.add(event) },
        )
        return received
    }

    private class RecordingUpstreamClient(
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

    companion object {
        private const val LOCAL_RESPONSE = "Generated on-device."
        private const val EVENT_DELIVERY_MS = 100L
    }
}
