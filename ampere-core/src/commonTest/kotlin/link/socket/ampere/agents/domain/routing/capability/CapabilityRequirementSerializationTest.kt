package link.socket.ampere.agents.domain.routing.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs

class CapabilityRequirementSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun fullyPopulatedRequirementRoundTrips() {
        val original = CapabilityRequirement(
            required = setOf(
                ProviderCapability.TOOL_CALLING,
                ProviderCapability.STRUCTURED_OUTPUT,
                ProviderCapability.LONG_CONTEXT,
            ),
            minReasoning = RelativeReasoning.HIGH,
            minContextTokens = 200_000,
            inputs = SupportedInputs.TEXT_AND_IMAGE,
        )

        val decoded = json.decodeFromString<CapabilityRequirement>(
            json.encodeToString(CapabilityRequirement.serializer(), original),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun emptyRequirementRoundTrips() {
        val original = CapabilityRequirement()

        val decoded = json.decodeFromString<CapabilityRequirement>(
            json.encodeToString(CapabilityRequirement.serializer(), original),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun everyProviderCapabilityRoundTrips() {
        for (capability in ProviderCapability.entries) {
            val decoded = json.decodeFromString<ProviderCapability>(
                json.encodeToString(ProviderCapability.serializer(), capability),
            )
            assertEquals(capability, decoded)
        }
    }
}
