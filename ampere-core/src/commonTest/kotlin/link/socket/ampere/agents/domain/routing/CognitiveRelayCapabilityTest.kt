package link.socket.ampere.agents.domain.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptor
import link.socket.ampere.agents.domain.routing.capability.ProviderCapability
import link.socket.ampere.agents.domain.routing.local.LocalCapacity
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

    // Descriptors are keyed by the model each config selects, not by provider.
    private val registry = InMemoryModelDescriptorRegistry(
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
                costPerWatt = 0.007,
            ),
        ),
    )

    // Anthropic's descriptor is availabilityGated, so a capacity snapshot must
    // report it available before the gate opens.
    private val localAvailable = LocalCapacity(
        available = true,
        providerId = AIProvider_Anthropic.id,
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
    fun `text-transform requirement selects available local provider`() = runTest {
        val context = RoutingContext(
            requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
            localCapacity = localAvailable,
        )

        val result = relay().resolveWithMetadata(context, agentFallback)

        assertEquals(AIModel_Claude.Sonnet_4, result.configuration.model)
        assertEquals("capability:${AIProvider_Anthropic.id}", result.reason)
    }

    @Test
    fun `unavailable local provider is skipped and grid is selected`() = runTest {
        val context = RoutingContext(
            requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
            localCapacity = LocalCapacity(
                available = false,
                providerId = AIProvider_Anthropic.id,
                reason = "apple_intelligence_unavailable",
            ),
        )

        val result = relay().resolveWithMetadata(context, agentFallback)

        assertEquals(AIModel_Gemini.Flash_2_5, result.configuration.model)
        assertEquals("capability:${AIProvider_Google.id}", result.reason)
    }

    @Test
    fun `null localCapacity leaves the availability gate closed`() = runTest {
        // Capable local provider, but no capacity snapshot -> gate stays shut.
        val context = RoutingContext(
            requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
        )

        val result = relay().resolveWithMetadata(context, agentFallback)

        assertEquals(AIModel_Gemini.Flash_2_5, result.configuration.model)
        assertEquals("capability:${AIProvider_Google.id}", result.reason)
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
    fun `HIGH reasoning requirement skips a sub-HIGH model from a capable provider`() = runTest {
        // Regression for the provider-keyed latent bug (AMPR-214): both rules
        // target Anthropic, but the first selects a LOW-reasoning model. When
        // selection was provider-keyed, the provider's HIGH descriptor let the
        // LOW model ride in and finalize. Model-keyed selection evaluates each
        // model's own tier, so the LOW model is skipped and the HIGH one wins.
        val lowModelConfig = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Haiku_3,
        )
        val highModelConfig = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Opus_4_5,
        )
        val tierRegistry = InMemoryModelDescriptorRegistry(
            seed = listOf(
                ModelDescriptor(
                    modelName = AIModel_Claude.Haiku_3.name,
                    providerId = AIProvider_Anthropic.id,
                    capabilities = emptySet(),
                    reasoning = RelativeReasoning.LOW,
                    maxContextTokens = 200_000,
                    supportedInputs = SupportedInputs.TEXT,
                ),
                ModelDescriptor(
                    modelName = AIModel_Claude.Opus_4_5.name,
                    providerId = AIProvider_Anthropic.id,
                    capabilities = emptySet(),
                    reasoning = RelativeReasoning.HIGH,
                    maxContextTokens = 200_000,
                    supportedInputs = SupportedInputs.TEXT,
                ),
            ),
        )
        val tierRelay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                // LOW model listed first; only the HIGH model satisfies the requirement.
                rules = listOf(
                    RoutingRule.ByCapability(lowModelConfig),
                    RoutingRule.ByCapability(highModelConfig),
                ),
            ),
            registry = tierRegistry,
        )

        val result = tierRelay.resolveWithMetadata(
            context = RoutingContext(
                requirements = CapabilityRequirement(minReasoning = RelativeReasoning.HIGH),
            ),
            fallbackConfiguration = agentFallback,
        )

        assertEquals(AIModel_Claude.Opus_4_5, result.configuration.model)
        assertFalse(result.configuration.model == AIModel_Claude.Haiku_3)
    }

    @Test
    fun `emits RouteSelected with local decision for text requirement`() = runBlocking {
        val decision = resolveCapturingDecision(
            CapabilityRequirement(inputs = SupportedInputs.TEXT),
        )

        assertEquals("Anthropic", decision.providerName)
        assertEquals(AIModel_Claude.Sonnet_4.name, decision.modelName)
        assertEquals("capability:${AIProvider_Anthropic.id}", decision.matchedRule)
        assertFalse(decision.isFallback)
    }

    @Test
    fun `emits RouteFallback when available local provider is gated out`() = runBlocking {
        val events = resolveCapturingEvents(
            requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
            localCapacity = LocalCapacity(
                available = false,
                providerId = AIProvider_Anthropic.id,
                reason = "thermal_throttle",
            ),
        )

        val fallback = events.filterIsInstance<RoutingEvent.RouteFallback>().single()
        assertEquals(AIProvider_Anthropic.id, fallback.failedProvider)
        assertEquals(AIModel_Claude.Sonnet_4.name, fallback.failedModel)
        assertEquals("thermal_throttle", fallback.failureReason)
        assertEquals("Google", fallback.fallbackDecision.providerName)
        assertEquals(AIModel_Gemini.Flash_2_5.name, fallback.fallbackDecision.modelName)

        // The grid pick is still announced, marked as a fallback.
        val selected = events.filterIsInstance<RoutingEvent.RouteSelected>().single()
        assertEquals("Google", selected.decision.providerName)
        assertTrue(selected.decision.isFallback)
    }

    @Test
    fun `emits no RouteFallback when local provider is available`() = runBlocking {
        val events = resolveCapturingEvents(
            requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
            localCapacity = localAvailable,
        )

        assertTrue(events.filterIsInstance<RoutingEvent.RouteFallback>().isEmpty())

        val selected = events.filterIsInstance<RoutingEvent.RouteSelected>().single()
        assertEquals("Anthropic", selected.decision.providerName)
        assertFalse(selected.decision.isFallback)
    }

    @Test
    fun `uses default reason when unavailable snapshot states none`() = runBlocking {
        val events = resolveCapturingEvents(
            requirements = CapabilityRequirement(inputs = SupportedInputs.TEXT),
            localCapacity = LocalCapacity(available = false, providerId = AIProvider_Anthropic.id),
        )

        val fallback = events.filterIsInstance<RoutingEvent.RouteFallback>().single()
        assertEquals(
            RoutingRule.ByCapability.DEFAULT_UNAVAILABLE_REASON,
            fallback.failureReason,
        )
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
        localCapacity: LocalCapacity? = localAvailable,
    ): RoutingDecision {
        val selected = resolveCapturingEvents(requirements, localCapacity)
            .filterIsInstance<RoutingEvent.RouteSelected>()
        assertTrue(selected.isNotEmpty(), "Expected a RouteSelected event")
        return selected.first().decision
    }

    private suspend fun resolveCapturingEvents(
        requirements: CapabilityRequirement,
        localCapacity: LocalCapacity? = localAvailable,
    ): List<Event> {
        val scope = CoroutineScope(Dispatchers.Default)
        val eventBus = EventSerialBus(scope)
        val received = mutableListOf<Event>()

        eventBus.subscribe(
            agentId = "selected-subscriber",
            eventType = RoutingEvent.RouteSelected.EVENT_TYPE,
            handler = EventHandler { event, _ -> received.add(event) },
        )
        eventBus.subscribe(
            agentId = "fallback-subscriber",
            eventType = RoutingEvent.RouteFallback.EVENT_TYPE,
            handler = EventHandler { event, _ -> received.add(event) },
        )

        relay(eventBus).resolve(
            RoutingContext(requirements = requirements, localCapacity = localCapacity),
            agentFallback,
        )

        delay(100)

        return received.toList()
    }
}
