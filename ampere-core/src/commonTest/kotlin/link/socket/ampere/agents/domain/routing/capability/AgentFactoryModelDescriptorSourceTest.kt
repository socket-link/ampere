package link.socket.ampere.agents.domain.routing.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingResolution
import link.socket.ampere.agents.domain.routing.RoutingRule
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google

/**
 * AMPR-231: exercises the exact registry construction [link.socket.ampere.agents.definition.AgentFactory]
 * now performs — a [ModelDescriptorSource] loaded eagerly into an
 * [InMemoryModelDescriptorRegistry] seed — so a consumer-supplied catalog governs
 * routing immediately, without a relay or rule list of their own.
 */
class AgentFactoryModelDescriptorSourceTest {

    private val fallbackConfig = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4,
    )

    private val customModelConfig = AIConfiguration_Default(
        provider = AIProvider_Google,
        model = AIModel_Gemini.Flash_2_5,
    )

    private val customDescriptor = ModelDescriptor(
        modelName = AIModel_Gemini.Flash_2_5.name,
        providerId = AIProvider_Google.id,
        capabilities = emptySet(),
        reasoning = RelativeReasoning.HIGH,
        maxContextTokens = 200_000,
        supportedInputs = SupportedInputs.TEXT,
        cost = CostPolicy.Free,
        rung = CapabilityRung.THREE,
    )

    private val customSource = ModelDescriptorSource { Result.success(listOf(customDescriptor)) }

    @Test
    fun `registry seeded from a supplied source holds only that source's catalog`() = runTest {
        // Mirrors AgentFactory.effectiveCognitiveRelay: seed loaded eagerly from the
        // source, not left to a future refresh().
        val registry = InMemoryModelDescriptorRegistry(
            seed = customSource.load().getOrThrow(),
            source = customSource,
        )

        assertEquals(listOf(customDescriptor), registry.all())
    }

    @Test
    fun `CODE agent floor routes to the sole model from a single-descriptor custom registry`() = runTest {
        // AgentFactory.DEFAULT_CODE_AGENT_RUNG is CapabilityRung.THREE; a consumer
        // handing in one descriptor at that rung must see the CODE path route to it
        // without supplying a relay or rule list themselves.
        val registry = InMemoryModelDescriptorRegistry(
            seed = customSource.load().getOrThrow(),
            source = customSource,
        )

        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(RoutingRule.ByCapability(customModelConfig)),
            ),
            registry = registry,
        )

        val result = relay.resolveWithMetadata(
            context = RoutingContext(requirements = CapabilityRequirement(minRung = CapabilityRung.THREE)),
            fallbackConfiguration = fallbackConfig,
        )

        assertIs<RoutingResolution.Success>(result)
        assertEquals(AIModel_Gemini.Flash_2_5, result.configuration.model)
    }

    @Test
    fun `default source composition is unaffected when no source is supplied`() = runTest {
        val defaultCatalog = DefaultModelDescriptorSource.load().getOrThrow()
        val noArgRegistry = InMemoryModelDescriptorRegistry()

        assertEquals(defaultCatalog.toSet(), noArgRegistry.all().toSet())
    }
}
