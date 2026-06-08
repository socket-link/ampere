package link.socket.ampere.agents.domain.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
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

class CognitiveRelayCostTest {

    private val anthropicConfig = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4,
    )
    private val googleConfig = AIConfiguration_Default(
        provider = AIProvider_Google,
        model = AIModel_Gemini.Flash_2_5,
    )
    private val openaiConfig = AIConfiguration_Default(
        provider = AIProvider_OpenAI,
        model = AIModel_OpenAI.GPT_4_1,
    )

    // Requirement every bundled cloud provider satisfies, so price is the only
    // differentiator.
    private val worldKnowledge = CapabilityRequirement(
        required = setOf(ProviderCapability.WORLD_KNOWLEDGE),
        inputs = SupportedInputs.TEXT,
    )

    // Default registry: Google (0.007) < OpenAI (0.014) < Anthropic (0.026).
    private fun defaultRelay(eventBus: EventSerialBus? = null) = CognitiveRelayImpl(
        initialConfig = RelayConfig(
            rules = listOf(
                // Listed Anthropic-first to prove cost overrides rule order.
                RoutingRule.ByCapability(anthropicConfig),
                RoutingRule.ByCapability(openaiConfig),
                RoutingRule.ByCapability(googleConfig),
            ),
        ),
        eventBus = eventBus,
        registry = InMemoryProviderDescriptorRegistry(),
    )

    @Test
    fun `selects cheapest capable provider Google then OpenAI then Anthropic`() = runTest {
        val result = defaultRelay().resolveWithMetadata(
            context = RoutingContext(requirements = worldKnowledge),
            fallbackConfiguration = anthropicConfig,
        )

        assertEquals(AIModel_Gemini.Flash_2_5, result.configuration.model)
        assertEquals("capability:${AIProvider_Google.id}", result.reason)
    }

    @Test
    fun `tie-break is stable by providerId regardless of rule order`() = runTest {
        // Both providers priced identically; Anthropic must win on id ("anthropic"
        // < "google") even though the Google rule is listed first.
        val registry = InMemoryProviderDescriptorRegistry(
            seed = listOf(
                tiedDescriptor(AIProvider_Anthropic.id),
                tiedDescriptor(AIProvider_Google.id),
            ),
        )
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByCapability(googleConfig),
                    RoutingRule.ByCapability(anthropicConfig),
                ),
            ),
            registry = registry,
        )

        val result = relay.resolveWithMetadata(
            context = RoutingContext(requirements = worldKnowledge),
            fallbackConfiguration = openaiConfig,
        )

        assertEquals(AIModel_Claude.Sonnet_4, result.configuration.model)
        assertEquals("capability:${AIProvider_Anthropic.id}", result.reason)
    }

    @Test
    fun `local Free provider always wins on price the 0W unification`() = runTest {
        // Anthropic stands in for a local 0W provider; Google is metered.
        val registry = InMemoryProviderDescriptorRegistry(
            seed = listOf(
                capableDescriptor(AIProvider_Anthropic.id, cost = CostPolicy.Free),
                capableDescriptor(AIProvider_Google.id, costPerWatt = 0.001),
            ),
        )
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByCapability(googleConfig),
                    RoutingRule.ByCapability(anthropicConfig),
                ),
            ),
            registry = registry,
        )

        val result = relay.resolve(
            context = RoutingContext(requirements = worldKnowledge),
            fallbackConfiguration = openaiConfig,
        )

        assertEquals(AIModel_Claude.Sonnet_4, result.model)
    }

    @Test
    fun `single capable candidate behaves exactly like first-match`() = runTest {
        // Only Google advertises world knowledge, so there is nothing to compare.
        val registry = InMemoryProviderDescriptorRegistry(
            seed = listOf(
                textOnlyDescriptor(AIProvider_Anthropic.id),
                capableDescriptor(AIProvider_Google.id, costPerWatt = 0.014),
            ),
        )
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByCapability(anthropicConfig),
                    RoutingRule.ByCapability(googleConfig),
                ),
            ),
            registry = registry,
        )

        val result = relay.resolveWithMetadata(
            context = RoutingContext(requirements = worldKnowledge),
            fallbackConfiguration = openaiConfig,
        )

        assertEquals(AIModel_Gemini.Flash_2_5, result.configuration.model)
        assertEquals("capability:${AIProvider_Google.id}", result.reason)
    }

    @Test
    fun `non-capability first match keeps pure first-match without cost-aware`() = runTest {
        // A phase rule matches before any capability rule, so it wins untouched.
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(CognitivePhase.PERCEIVE, anthropicConfig),
                    RoutingRule.ByCapability(googleConfig),
                ),
            ),
            registry = InMemoryProviderDescriptorRegistry(),
        )

        val result = relay.resolveWithMetadata(
            context = RoutingContext(
                phase = CognitivePhase.PERCEIVE,
                requirements = worldKnowledge,
            ),
            fallbackConfiguration = openaiConfig,
        )

        assertEquals(AIModel_Claude.Sonnet_4, result.configuration.model)
        assertEquals("phase:PERCEIVE", result.reason)
    }

    @Test
    fun `emits RouteResolved with chosen runner-up and savings`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val eventBus = EventSerialBus(scope)
        val received = mutableListOf<Event>()
        eventBus.subscribe(
            agentId = "test-subscriber",
            eventType = RoutingEvent.RouteResolved.EVENT_TYPE,
            handler = EventHandler { event, _ -> received.add(event) },
        )

        defaultRelay(eventBus).resolve(
            context = RoutingContext(requirements = worldKnowledge),
            fallbackConfiguration = anthropicConfig,
        )
        delay(100)

        assertTrue(received.isNotEmpty(), "Expected a RouteResolved event")
        val event = received.first() as RoutingEvent.RouteResolved
        assertEquals("Google", event.decision.providerName)
        assertEquals(3, event.candidateCount)
        assertEquals(0.007, event.estimatedWattCost, absoluteTolerance = 1e-9)
        assertEquals("OpenAI", event.runnerUpProvider)
        assertEquals(0.014, event.runnerUpWattCost!!, absoluteTolerance = 1e-9)
        assertEquals(0.007, event.savingsVsRunnerUp!!, absoluteTolerance = 1e-9)
        assertEquals(RelativeReasoning.HIGH, event.tier)
    }

    @Test
    fun `single candidate RouteResolved reports no runner-up`() = runBlocking {
        val registry = InMemoryProviderDescriptorRegistry(
            seed = listOf(
                textOnlyDescriptor(AIProvider_Anthropic.id),
                capableDescriptor(AIProvider_Google.id, costPerWatt = 0.014),
            ),
        )
        val scope = CoroutineScope(Dispatchers.Default)
        val eventBus = EventSerialBus(scope)
        val received = mutableListOf<Event>()
        eventBus.subscribe(
            agentId = "test-subscriber",
            eventType = RoutingEvent.RouteResolved.EVENT_TYPE,
            handler = EventHandler { event, _ -> received.add(event) },
        )

        CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByCapability(anthropicConfig),
                    RoutingRule.ByCapability(googleConfig),
                ),
            ),
            eventBus = eventBus,
            registry = registry,
        ).resolve(
            context = RoutingContext(requirements = worldKnowledge),
            fallbackConfiguration = openaiConfig,
        )
        delay(100)

        assertTrue(received.isNotEmpty(), "Expected a RouteResolved event")
        val event = received.first() as RoutingEvent.RouteResolved
        assertEquals(1, event.candidateCount)
        assertNull(event.runnerUpProvider)
        assertNull(event.savingsVsRunnerUp)
    }

    private fun capableDescriptor(
        providerId: String,
        cost: CostPolicy = CostPolicy.Metered,
        costPerWatt: Double = 0.014,
    ) = ProviderDescriptor(
        providerId = providerId,
        capabilities = setOf(
            ProviderCapability.WORLD_KNOWLEDGE,
            ProviderCapability.TOOL_CALLING,
            ProviderCapability.LONG_CONTEXT,
        ),
        reasoning = RelativeReasoning.HIGH,
        maxContextTokens = 200_000,
        supportedInputs = SupportedInputs.TEXT_AND_IMAGE,
        cost = cost,
        costPerWatt = costPerWatt,
    )

    private fun tiedDescriptor(providerId: String) =
        capableDescriptor(providerId, costPerWatt = 0.01)

    private fun textOnlyDescriptor(providerId: String) = ProviderDescriptor(
        providerId = providerId,
        capabilities = emptySet(),
        reasoning = RelativeReasoning.LOW,
        maxContextTokens = 8_192,
        supportedInputs = SupportedInputs.TEXT,
        cost = CostPolicy.Free,
    )
}
