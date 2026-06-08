package link.socket.ampere.agents.domain.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.InMemoryProviderDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ProviderCapability
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptor
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class CognitiveRelayCapabilityTest {

    // Anthropic stands in for the local provider: text-only, no world knowledge.
    private val localConfig = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4,
    )

    // Google stands in for the cloud "grid" provider: full capabilities.
    private val gridConfig = AIConfiguration_Default(
        provider = AIProvider_Google,
        model = AIModel_Gemini.Flash_2_5,
    )

    private val agentFallback = AIConfiguration_Default(
        provider = AIProvider_OpenAI,
        model = AIModel_OpenAI.GPT_4_1,
    )

    private val registry = InMemoryProviderDescriptorRegistry(
        seed = listOf(
            ProviderDescriptor(
                providerId = AIProvider_Anthropic.id,
                capabilities = emptySet(),
                reasoning = RelativeReasoning.LOW,
                maxContextTokens = 8_192,
                supportedInputs = SupportedInputs.TEXT,
                cost = CostPolicy.Free,
                availabilityGated = true,
            ),
            ProviderDescriptor(
                providerId = AIProvider_Google.id,
                capabilities = setOf(
                    ProviderCapability.WORLD_KNOWLEDGE,
                    ProviderCapability.TOOL_CALLING,
                    ProviderCapability.LONG_CONTEXT,
                ),
                reasoning = RelativeReasoning.HIGH,
                maxContextTokens = 200_000,
                supportedInputs = SupportedInputs.TEXT_AND_IMAGE,
                cost = CostPolicy.Metered(usdPerWatt = 0.007),
            ),
        ),
    )

    // Local-first ordering: the relay's first-match picks the first capable provider.
    private fun relay(eventBus: EventSerialBus? = null) = CognitiveRelayImpl(
        initialConfig = RelayConfig(
            rules = listOf(
                RoutingRule.ByCapability(localConfig),
                RoutingRule.ByCapability(gridConfig),
            ),
        ),
        eventBus = eventBus,
        registry = registry,
    )

    @Test
    fun `text-transform requirement selects local provider`() = runTest {
        val context = RoutingContext(
            requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
        )

        val result = relay().resolveWithMetadata(context, agentFallback)

        assertEquals(AIModel_Claude.Sonnet_4, result.configuration.model)
        assertEquals("capability:${AIProvider_Anthropic.id}", result.reason)
    }

    @Test
    fun `world-knowledge requirement falls through to grid provider`() = runTest {
        val context = RoutingContext(
            requirements = CapabilityRequirement(
                required = setOf(ProviderCapability.WORLD_KNOWLEDGE),
                inputs = SupportedInputs.TEXT,
            ),
        )

        val result = relay().resolveWithMetadata(context, agentFallback)

        assertEquals(AIModel_Gemini.Flash_2_5, result.configuration.model)
        assertEquals("capability:${AIProvider_Google.id}", result.reason)
    }

    @Test
    fun `no requirements falls back to agent config`() = runTest {
        // ByCapability never matches without requirements, so no rule applies.
        val result = relay().resolveWithMetadata(RoutingContext(), agentFallback)

        assertEquals(AIModel_OpenAI.GPT_4_1, result.configuration.model)
        assertEquals("default", result.reason)
    }

    @Test
    fun `emits RouteSelected with local decision for text requirement`() = runBlocking {
        val decision = resolveCapturingDecision(
            CapabilityRequirement(inputs = SupportedInputs.TEXT),
        )

        assertEquals("Anthropic", decision.providerName)
        assertEquals(AIModel_Claude.Sonnet_4.name, decision.modelName)
        assertEquals("capability:${AIProvider_Anthropic.id}", decision.matchedRule)
    }

    @Test
    fun `emits RouteSelected with grid decision for world-knowledge requirement`() = runBlocking {
        val decision = resolveCapturingDecision(
            CapabilityRequirement(
                required = setOf(ProviderCapability.WORLD_KNOWLEDGE),
                inputs = SupportedInputs.TEXT,
            ),
        )

        assertEquals("Google", decision.providerName)
        assertEquals(AIModel_Gemini.Flash_2_5.name, decision.modelName)
        assertEquals("capability:${AIProvider_Google.id}", decision.matchedRule)
    }

    private suspend fun resolveCapturingDecision(
        requirements: CapabilityRequirement,
    ): RoutingDecision {
        val scope = CoroutineScope(Dispatchers.Default)
        val eventBus = EventSerialBus(scope)
        val received = mutableListOf<Event>()

        eventBus.subscribe(
            agentId = "test-subscriber",
            eventType = RoutingEvent.RouteSelected.EVENT_TYPE,
            handler = EventHandler { event, _ -> received.add(event) },
        )

        relay(eventBus).resolve(RoutingContext(requirements = requirements), agentFallback)

        delay(100)

        assertTrue(received.isNotEmpty(), "Expected a RouteSelected event")
        return (received.first() as RoutingEvent.RouteSelected).decision
    }
}
