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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.CapabilityRoutingDefaults
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.local.FakeLocalInferenceEngine
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice

/**
 * End-to-end routing tests for Rung 0 (AMPR-225): the 0-Watt, on-device floor
 * seeded into the default [InMemoryModelDescriptorRegistry] catalog and
 * [CapabilityRoutingDefaults.defaultCapabilityRules]. Mirrors
 * [LocalInferenceRelayIntegrationTest]'s pattern but exercises the real
 * on-device provider/model/descriptor instead of a cloud provider standing in
 * for one.
 */
class Rung0RoutingTest {

    private val agentFallback = AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels)

    private fun relayAndRegistry(
        eventBus: EventSerialBus? = null,
    ): Pair<CognitiveRelayImpl, InMemoryModelDescriptorRegistry> {
        val registry = InMemoryModelDescriptorRegistry()
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(rules = CapabilityRoutingDefaults.defaultCapabilityRules()),
            eventBus = eventBus,
            registry = registry,
        )
        return relay to registry
    }

    @Test
    fun `Rung 0 is selected and executed on-device when available`() = runBlocking {
        val eventBus = EventSerialBus(CoroutineScope(Dispatchers.Default))
        val routeSelections = subscribeTo(eventBus, RoutingEvent.RouteSelected.EVENT_TYPE)
        val (relay, registry) = relayAndRegistry(eventBus)

        val engine = FakeLocalInferenceEngine(respond = { Result.success(LOCAL_RESPONSE) })
        val cloud = RecordingUpstreamClient("cloud-response")

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
                localCapacity = LocalCapacity(available = true, providerId = AIProvider_OnDevice.id),
            ),
        )

        assertEquals(LOCAL_RESPONSE, response)
        assertEquals(1, engine.generateCount, "on-device engine must have executed exactly once")
        assertEquals(0, cloud.callCount, "cloud path must not be touched for the free on-device route")

        delay(EVENT_DELIVERY_MS)
        assertEquals(1, routeSelections.size, "expected exactly one RouteSelected event")
        val decision = (routeSelections.first() as RoutingEvent.RouteSelected).decision
        assertEquals(AIProvider_OnDevice.name, decision.providerName)
        assertEquals(AIModel_OnDevice.AppleFoundationModels.name, decision.modelName)
        assertEquals("capability:${AIProvider_OnDevice.id}", decision.matchedRule)
    }

    @Test
    fun `Rung 0 falls back to a metered cloud rung when on-device is unavailable`() = runBlocking {
        val eventBus = EventSerialBus(CoroutineScope(Dispatchers.Default))
        val routeFallbacks = subscribeTo(eventBus, RoutingEvent.RouteFallback.EVENT_TYPE)
        val (relay, registry) = relayAndRegistry(eventBus)

        val engine = FakeLocalInferenceEngine(respond = { Result.success(LOCAL_RESPONSE) })
        val cloud = RecordingUpstreamClient("cloud-response")

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
                // No required capability beyond TEXT input, so the on-device
                // model would satisfy the requirement too — this isolates the
                // fallback to the availability gate, not a capability gap.
                requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
                // No LocalCapacity supplied: the gate stays closed (unavailable).
            ),
        )

        assertEquals("cloud-response", response)
        assertEquals(0, engine.generateCount, "on-device engine must not run once the gate is closed")
        assertEquals(1, cloud.callCount, "the metered grid provider must execute the call")

        delay(EVENT_DELIVERY_MS)
        assertTrue(routeFallbacks.isNotEmpty(), "expected a RouteFallback event for the closed on-device gate")
        val fallback = routeFallbacks.first() as RoutingEvent.RouteFallback
        assertEquals(AIProvider_OnDevice.id, fallback.failedProvider)
    }

    @Test
    fun `Rung 0 selection reports 0W with on-device provenance`() = runBlocking {
        val eventBus = EventSerialBus(CoroutineScope(Dispatchers.Default))
        val routeResolutions = subscribeTo(eventBus, RoutingEvent.RouteResolved.EVENT_TYPE)
        val (relay, _) = relayAndRegistry(eventBus)

        relay.resolveWithMetadata(
            context = RoutingContext(
                requirements = CapabilityRequirement(
                    minRung = CapabilityRung.ZERO,
                    inputs = SupportedInputs.TEXT,
                ),
                localCapacity = LocalCapacity(available = true, providerId = AIProvider_OnDevice.id),
            ),
            fallbackConfiguration = agentFallback,
        )
        delay(EVENT_DELIVERY_MS)

        assertTrue(routeResolutions.isNotEmpty(), "expected a RouteResolved event")
        val resolved = routeResolutions.first() as RoutingEvent.RouteResolved
        assertEquals(AIProvider_OnDevice.name, resolved.decision.providerName)
        assertEquals(0.0, resolved.estimatedWattCost, "on-device route must cost 0 Watts")
    }

    private fun subscribeTo(eventBus: EventSerialBus, eventType: String): MutableList<Event> {
        val received = mutableListOf<Event>()
        eventBus.subscribe(
            agentId = "route-subscriber",
            eventType = eventType,
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
