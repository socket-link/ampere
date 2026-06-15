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
    fun rungOneModels() {
        val expected = listOf(
            "claude-3-haiku-20240307",
            "gpt-5-nano",
            "gpt-4o-mini",
            "gemini-2.0-flash-lite",
            "gemini-2.5-flash-lite",
        )
        expected.forEach { name ->
            assertEquals(CapabilityRung.ONE, descriptors[name]?.rung, "$name should be rung ONE")
        }
    }

    @Test
    fun rungTwoModels() {
        val expected = listOf(
            "claude-3-5-haiku-latest",
            "claude-haiku-4-5",
            "gpt-5-mini",
            "gpt-4.1-mini",
            "gpt-4o",
            "o3-mini",
            "gemini-2.0-flash",
            "gemini-2.5-flash",
        )
        expected.forEach { name ->
            assertEquals(CapabilityRung.TWO, descriptors[name]?.rung, "$name should be rung TWO")
        }
    }

    @Test
    fun rungThreeModels() {
        val expected = listOf(
            "claude-sonnet-4-0",
            "claude-3-7-sonnet-latest",
            "claude-sonnet-4-5-20250929",
            "gpt-4.1",
            "o4-mini",
            "gemini-2.5-pro",
        )
        expected.forEach { name ->
            assertEquals(CapabilityRung.THREE, descriptors[name]?.rung, "$name should be rung THREE")
        }
    }

    @Test
    fun rungFourModels() {
        val expected = listOf(
            "claude-opus-4-0",
            "claude-opus-4-1",
            "claude-opus-4-5-20251101",
            "gpt-5",
            "gpt-5.1",
            "gpt-5.1-chat-latest",
            "gpt-5.1-codex-max",
            "o3",
            "gemini-3-pro-latest",
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
                d.rung > CapabilityRung.TWO
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
