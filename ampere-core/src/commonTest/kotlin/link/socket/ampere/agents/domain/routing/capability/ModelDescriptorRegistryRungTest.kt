package link.socket.ampere.agents.domain.routing.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates rung seeding for the bundled cloud model catalog (AMPR-218).
 */
class ModelDescriptorRegistryRungTest {

    private val descriptors: Map<String, ModelDescriptor> =
        InMemoryModelDescriptorRegistry.defaultModelDescriptors()
            .associateBy { it.modelName }

    @Test
    fun everyModelHasARung() {
        val models = descriptors.keys
        assertTrue(models.isNotEmpty(), "descriptor list must not be empty")
        models.forEach { name ->
            assertNotNull(descriptors[name]?.rung, "missing rung for $name")
        }
    }

    // ── Spot-checks by rung ───────────────────────────────────────────────────

    @Test
    fun rungZeroModels() {
        // AMPR-225: the on-device floor is always 0W, always availability-gated.
        val descriptor = descriptors["apple-foundation-models-on-device"]
        assertEquals(CapabilityRung.ZERO, descriptor?.rung, "on-device model should be rung ZERO")
        assertEquals(CostPolicy.Free, descriptor?.cost, "on-device model should be 0W")
        assertEquals(true, descriptor?.availabilityGated, "on-device model must be availability-gated")
        assertTrue(
            descriptor?.capabilities?.contains(ProviderCapability.STRUCTURED_OUTPUT) == true,
            "on-device model should advertise STRUCTURED_OUTPUT",
        )
    }

    @Test
    fun rungOneModels() {
        val expected = listOf(
            "gpt-5-nano",
            "gpt-4o-mini",
            "gpt-5.6-luna",
            "gemini-2.5-flash-lite",
            "gemini-3.5-flash-lite",
        )
        expected.forEach { name ->
            assertEquals(CapabilityRung.ONE, descriptors[name]?.rung, "$name should be rung ONE")
        }
    }

    @Test
    fun rungTwoModels() {
        val expected = listOf(
            "claude-haiku-4-5",
            "claude-haiku-5-5",
            "gpt-5-mini",
            "gpt-4.1-mini",
            "gpt-4o",
            "o3-mini",
            "gpt-5.4-mini",
            "gemini-2.5-flash",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gpt-6-luna",
        )
        expected.forEach { name ->
            assertEquals(CapabilityRung.TWO, descriptors[name]?.rung, "$name should be rung TWO")
        }
    }

    @Test
    fun rungThreeModels() {
        val expected = listOf(
            "claude-sonnet-4-5-20250929",
            "claude-sonnet-4-6",
            "claude-sonnet-5",
            "claude-sonnet-5-5",
            "gpt-4.1",
            "gpt-5.6-terra",
            "gemini-2.5-pro",
        )
        expected.forEach { name ->
            assertEquals(CapabilityRung.THREE, descriptors[name]?.rung, "$name should be rung THREE")
        }
    }

    @Test
    fun rungFourModels() {
        val expected = listOf(
            "claude-opus-4-5-20251101",
            "claude-opus-4-6",
            "claude-opus-4-7",
            "claude-opus-4-8",
            "claude-opus-5",
            "claude-fable-5-1",
            "claude-opus-5-5",
            "gpt-5",
            "gpt-5.1",
            "gpt-5.4",
            "gpt-5.5",
            "gpt-5.6-sol",
            "gpt-6-sol",
            "o3",
            "gemini-3.1-pro-preview",
            "gemini-4-argon",
        )
        expected.forEach { name ->
            assertEquals(CapabilityRung.FOUR, descriptors[name]?.rung, "$name should be rung FOUR")
        }
    }

    // ── Sanity-order: rung must be consistent with reasoningLevel ────────────

    @Test
    fun noLowReasoningModelExceedsRungTwo() {
        val violations = descriptors.values.filter { d ->
            d.reasoning == link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning.LOW &&
                (d.rung ?: CapabilityRung.ONE) > CapabilityRung.TWO
        }
        assertTrue(violations.isEmpty(), "LOW-reasoning models above rung TWO: ${violations.map { it.modelName }}")
    }

    @Test
    fun noRungFourModelIsBelowHighReasoning() {
        val violations = descriptors.values.filter { d ->
            d.rung == CapabilityRung.FOUR &&
                d.reasoning != link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning.HIGH
        }
        assertTrue(violations.isEmpty(), "FOUR-rung models without HIGH reasoning: ${violations.map { it.modelName }}")
    }
}
