package link.socket.ampere.agents.domain.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptor
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

class CognitiveRelayFloorTest {

    private val lowRungConfig = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4,
    )

    private val highRungConfig = AIConfiguration_Default(
        provider = AIProvider_Google,
        model = AIModel_Gemini.Flash_2_5,
    )

    private val agentFallback = AIConfiguration_Default(
        provider = AIProvider_OpenAI,
        model = AIModel_OpenAI.GPT_4_1,
    )

    // Registry where all models are rung ONE — no model can satisfy a floor >= TWO.
    private val lowRungRegistry = InMemoryModelDescriptorRegistry(
        seed = listOf(
            ModelDescriptor(
                modelName = AIModel_Claude.Sonnet_4.name,
                providerId = AIProvider_Anthropic.id,
                capabilities = emptySet(),
                reasoning = RelativeReasoning.LOW,
                maxContextTokens = 8_192,
                supportedInputs = SupportedInputs.TEXT,
                cost = CostPolicy.Free,
                rung = CapabilityRung.ONE,
            ),
            ModelDescriptor(
                modelName = AIModel_Gemini.Flash_2_5.name,
                providerId = AIProvider_Google.id,
                capabilities = emptySet(),
                reasoning = RelativeReasoning.NORMAL,
                maxContextTokens = 200_000,
                supportedInputs = SupportedInputs.TEXT,
                cost = CostPolicy.Metered,
                rung = CapabilityRung.TWO,
            ),
        ),
    )

    // Registry with a rung THREE model.
    private val highRungRegistry = InMemoryModelDescriptorRegistry(
        seed = listOf(
            ModelDescriptor(
                modelName = AIModel_Claude.Sonnet_4.name,
                providerId = AIProvider_Anthropic.id,
                capabilities = emptySet(),
                reasoning = RelativeReasoning.LOW,
                maxContextTokens = 8_192,
                supportedInputs = SupportedInputs.TEXT,
                cost = CostPolicy.Free,
                rung = CapabilityRung.ONE,
            ),
            ModelDescriptor(
                modelName = AIModel_Gemini.Flash_2_5.name,
                providerId = AIProvider_Google.id,
                capabilities = emptySet(),
                reasoning = RelativeReasoning.HIGH,
                maxContextTokens = 200_000,
                supportedInputs = SupportedInputs.TEXT,
                cost = CostPolicy.Metered,
                rung = CapabilityRung.THREE,
            ),
        ),
    )

    private fun relayWith(registry: InMemoryModelDescriptorRegistry, eventBus: EventSerialBus? = null) =
        CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByCapability(lowRungConfig),
                    RoutingRule.ByCapability(highRungConfig),
                ),
            ),
            eventBus = eventBus,
            registry = registry,
        )

    @Test
    fun `resolveWithMetadata returns FloorUnmet when no model meets the floor`() = runTest {
        val result = relayWith(lowRungRegistry).resolveWithMetadata(
            context = RoutingContext(requirements = CapabilityRequirement(minRung = CapabilityRung.THREE)),
            fallbackConfiguration = agentFallback,
        )

        assertIs<RoutingResolution.FloorUnmet>(result)
        assertEquals(CapabilityRung.THREE, result.requestedFloor)
        // Best available is rung TWO (from Gemini entry in lowRungRegistry).
        assertEquals(CapabilityRung.TWO, result.bestAvailableRung)
    }

    @Test
    fun `resolveWithMetadata returns Success when a model meets the floor`() = runTest {
        val result = relayWith(highRungRegistry).resolveWithMetadata(
            context = RoutingContext(requirements = CapabilityRequirement(minRung = CapabilityRung.THREE)),
            fallbackConfiguration = agentFallback,
        )

        assertIs<RoutingResolution.Success>(result)
        assertEquals(AIModel_Gemini.Flash_2_5, result.configuration.model)
    }

    @Test
    fun `floor-unmet is terminal — does not silently downgrade to sub-floor agent config`() = runTest {
        // Without the fix, this would fall through to agentFallback (rung ONE).
        val result = relayWith(lowRungRegistry).resolveWithMetadata(
            context = RoutingContext(requirements = CapabilityRequirement(minRung = CapabilityRung.THREE)),
            fallbackConfiguration = agentFallback,
        )

        assertIs<RoutingResolution.FloorUnmet>(result)
    }

    @Test
    fun `resolve throws RoutingFloorUnmetException when floor is unmet`() = runTest {
        val ex = assertFailsWith<RoutingFloorUnmetException> {
            relayWith(lowRungRegistry).resolve(
                context = RoutingContext(requirements = CapabilityRequirement(minRung = CapabilityRung.THREE)),
                fallbackConfiguration = agentFallback,
            )
        }

        assertEquals(CapabilityRung.THREE, ex.requestedFloor)
        assertEquals(CapabilityRung.TWO, ex.bestAvailableRung)
    }

    @Test
    fun `no minRung requirement resolves normally without triggering floor-unmet`() = runTest {
        // A requirement with no minRung must not produce FloorUnmet regardless of which
        // model wins — floor-unmet detection must only fire when minRung is set.
        val result = relayWith(lowRungRegistry).resolveWithMetadata(
            context = RoutingContext(requirements = CapabilityRequirement()),
            fallbackConfiguration = agentFallback,
        )

        assertIs<RoutingResolution.Success>(result)
    }

    @Test
    fun `emits RouteFloorUnmet event when floor is unmet`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val eventBus = EventSerialBus(scope)
        val received = mutableListOf<RoutingEvent.RouteFloorUnmet>()

        eventBus.subscribe(
            agentId = "floor-subscriber",
            eventType = RoutingEvent.RouteFloorUnmet.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                received.add(event as RoutingEvent.RouteFloorUnmet)
            },
        )

        relayWith(lowRungRegistry, eventBus).resolveWithMetadata(
            context = RoutingContext(
                requirements = CapabilityRequirement(minRung = CapabilityRung.THREE),
                agentId = "test-agent",
            ),
            fallbackConfiguration = agentFallback,
        )

        delay(100)

        val event = received.single()
        assertEquals(CapabilityRung.THREE, event.requestedFloor)
        assertEquals(CapabilityRung.TWO, event.bestAvailableRung)
        assertEquals("test-agent", event.agentId)
    }

    @Test
    fun `floor-unmet does not emit RouteSelected`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val eventBus = EventSerialBus(scope)
        val selected = mutableListOf<RoutingEvent.RouteSelected>()

        eventBus.subscribe(
            agentId = "selected-subscriber",
            eventType = RoutingEvent.RouteSelected.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                selected.add(event as RoutingEvent.RouteSelected)
            },
        )

        relayWith(lowRungRegistry, eventBus).resolveWithMetadata(
            context = RoutingContext(requirements = CapabilityRequirement(minRung = CapabilityRung.THREE)),
            fallbackConfiguration = agentFallback,
        )

        delay(100)

        assertEquals(0, selected.size, "RouteSelected must not be emitted when floor is unmet")
    }

    @Test
    fun `FloorUnmet carries null bestAvailableRung when registry has no descriptors for rules`() = runTest {
        val emptyRegistry = InMemoryModelDescriptorRegistry(seed = emptyList())
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(RoutingRule.ByCapability(lowRungConfig)),
            ),
            registry = emptyRegistry,
        )

        // No descriptors → bestRungAmongCapabilityRules returns null; no match fires floor-unmet.
        // However without a descriptor, ByCapability rules evaluate as NoMatch, which means
        // no rule matches and minRung is set → detect floor-unmet with null best rung.
        val result = relay.resolveWithMetadata(
            context = RoutingContext(requirements = CapabilityRequirement(minRung = CapabilityRung.TWO)),
            fallbackConfiguration = agentFallback,
        )

        assertIs<RoutingResolution.FloorUnmet>(result)
        assertNull(result.bestAvailableRung)
    }
}
