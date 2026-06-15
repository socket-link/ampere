package link.socket.ampere.agents.domain.routing.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

class ModelDescriptorTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val opus = ModelDescriptor(
        modelName = AIModel_Claude.Opus_4_5.name,
        providerId = AIProvider_Anthropic.id,
        capabilities = setOf(
            ProviderCapability.WORLD_KNOWLEDGE,
            ProviderCapability.TOOL_CALLING,
            ProviderCapability.LONG_CONTEXT,
        ),
        reasoning = RelativeReasoning.HIGH,
        maxContextTokens = 200_000,
        supportedInputs = SupportedInputs.TEXT_AND_IMAGE,
    )

    @Test
    fun descriptorRoundTrips() {
        val decoded = json.decodeFromString<ModelDescriptor>(
            json.encodeToString(ModelDescriptor.serializer(), opus),
        )
        assertEquals(opus, decoded)
    }

    @Test
    fun freeCostPolicyRoundTrips() {
        val local = opus.copy(cost = CostPolicy.Free, availabilityGated = true)
        val decoded = json.decodeFromString<ModelDescriptor>(
            json.encodeToString(ModelDescriptor.serializer(), local),
        )
        assertEquals(local, decoded)
        assertEquals(CostPolicy.Free, decoded.cost)
    }

    @Test
    fun satisfiesAcceptsMetRequirement() {
        val req = CapabilityRequirement(
            required = setOf(ProviderCapability.TOOL_CALLING),
            minReasoning = RelativeReasoning.NORMAL,
            minContextTokens = 128_000,
            inputs = SupportedInputs.TEXT,
        )
        assertTrue(opus.satisfies(req))
    }

    @Test
    fun satisfiesRejectsUnmetRequirement() {
        // Requires a capability the descriptor does not advertise, a larger
        // context window, and an unsupported input modality.
        val req = CapabilityRequirement(
            required = setOf(ProviderCapability.STREAMING),
            minContextTokens = 1_000_000,
            inputs = SupportedInputs.ALL,
        )
        assertFalse(opus.satisfies(req))
    }

    @Test
    fun satisfiesRejectsSubTierReasoning() {
        // A LOW-reasoning model must not satisfy a HIGH requirement — the model
        // tier is evaluated directly, not its provider's best model (AMPR-214).
        val haiku = opus.copy(
            modelName = AIModel_Claude.Haiku_3.name,
            reasoning = RelativeReasoning.LOW,
        )
        assertFalse(haiku.satisfies(CapabilityRequirement(minReasoning = RelativeReasoning.HIGH)))
    }

    @Test
    fun emptyRequirementMatchesAnything() {
        assertTrue(opus.satisfies(CapabilityRequirement()))
    }

    @Test
    fun defaultRegistrySeedsOneDescriptorPerModel() = runTest {
        val registry = InMemoryModelDescriptorRegistry()

        // One descriptor per model across every bundled provider.
        val expectedCount = AIProvider.ALL_PROVIDERS.sumOf { it.availableModels.size }
        assertEquals(expectedCount, registry.all().size)

        // Each is keyed by model name and projects that model's own tier.
        val opusDescriptor = assertNotNull(registry.descriptorFor(AIModel_Claude.Opus_4_5.name))
        assertEquals(RelativeReasoning.HIGH, opusDescriptor.reasoning)
        assertEquals(AIProvider_Anthropic.id, opusDescriptor.providerId)

        val haikuDescriptor = assertNotNull(registry.descriptorFor(AIModel_Claude.Haiku_3.name))
        assertEquals(RelativeReasoning.LOW, haikuDescriptor.reasoning)

        // Gemini's 1M window projects through ModelLimits, not a provider default.
        val proDescriptor = assertNotNull(registry.descriptorFor(AIModel_Gemini.Pro_2_5.name))
        assertEquals(1_000_000, proDescriptor.maxContextTokens)

        assertNull(registry.descriptorFor("nonexistent-model"))
    }

    @Test
    fun registerReplacesDescriptor() = runTest {
        val registry = InMemoryModelDescriptorRegistry(seed = emptyList())
        assertNull(registry.descriptorFor(opus.modelName))

        registry.register(opus)
        assertEquals(opus, registry.descriptorFor(opus.modelName))

        val updated = opus.copy(maxContextTokens = 500_000)
        registry.register(updated)
        assertEquals(updated, registry.descriptorFor(opus.modelName))
        assertEquals(1, registry.all().size)
    }
}
