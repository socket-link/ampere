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
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class ProviderDescriptorTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val anthropic = ProviderDescriptor(
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
        val decoded = json.decodeFromString<ProviderDescriptor>(
            json.encodeToString(ProviderDescriptor.serializer(), anthropic),
        )
        assertEquals(anthropic, decoded)
    }

    @Test
    fun freeCostPolicyRoundTrips() {
        val local = anthropic.copy(cost = CostPolicy.Free, availabilityGated = true)
        val decoded = json.decodeFromString<ProviderDescriptor>(
            json.encodeToString(ProviderDescriptor.serializer(), local),
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
        assertTrue(anthropic.satisfies(req))
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
        assertFalse(anthropic.satisfies(req))
    }

    @Test
    fun emptyRequirementMatchesAnything() {
        assertTrue(anthropic.satisfies(CapabilityRequirement()))
    }

    @Test
    fun defaultRegistrySeedsThreeCloudProviders() = runTest {
        val registry = InMemoryProviderDescriptorRegistry()

        assertNotNull(registry.descriptorFor(AIProvider_Anthropic.id))
        assertNotNull(registry.descriptorFor(AIProvider_Google.id))
        assertNotNull(registry.descriptorFor(AIProvider_OpenAI.id))
        assertEquals(3, registry.all().size)
        assertNull(registry.descriptorFor("nonexistent"))
    }

    @Test
    fun registerReplacesDescriptor() = runTest {
        val registry = InMemoryProviderDescriptorRegistry(seed = emptyList())
        assertNull(registry.descriptorFor(anthropic.providerId))

        registry.register(anthropic)
        assertEquals(anthropic, registry.descriptorFor(anthropic.providerId))

        val updated = anthropic.copy(maxContextTokens = 500_000)
        registry.register(updated)
        assertEquals(updated, registry.descriptorFor(anthropic.providerId))
        assertEquals(1, registry.all().size)
    }
}
