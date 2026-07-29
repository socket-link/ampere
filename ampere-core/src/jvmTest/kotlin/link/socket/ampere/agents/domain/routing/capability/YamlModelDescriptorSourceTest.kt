package link.socket.ampere.agents.domain.routing.capability

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * The YAML-backed [ModelDescriptorSource] (AMPR-235): overrides layer over a
 * base catalog rather than replacing it, following the same "user file
 * overrides base" precedence as `ArcConfigLoader`.
 */
class YamlModelDescriptorSourceTest {

    private fun descriptor(
        name: String,
        rung: CapabilityRung? = CapabilityRung.ONE,
    ): ModelDescriptor = ModelDescriptor(
        modelName = name,
        providerId = AIProvider_Anthropic.id,
        capabilities = emptySet(),
        reasoning = RelativeReasoning.NORMAL,
        maxContextTokens = 200_000,
        supportedInputs = SupportedInputs.TEXT,
        rung = rung,
    )

    private fun tempFile(content: String? = null): File {
        val file = File.createTempFile("model-rungs", ".yaml")
        file.deleteOnExit()
        if (content == null) {
            file.delete()
        } else {
            file.writeText(content)
        }
        return file
    }

    // ── Absent/empty file reproduces base exactly ─────────────────────────────

    @Test
    fun `an absent file reproduces the base catalog unchanged`() = runTest {
        val base = listOf(descriptor("model-a", CapabilityRung.TWO))
        val source = YamlModelDescriptorSource(file = tempFile(content = null), base = { base })

        val result = source.load()

        assertTrue(result.isSuccess)
        assertEquals(base, result.getOrThrow())
    }

    @Test
    fun `an empty file reproduces the base catalog unchanged`() = runTest {
        val base = listOf(descriptor("model-a", CapabilityRung.TWO))
        val source = YamlModelDescriptorSource(file = tempFile(""), base = { base })

        val result = source.load()

        assertTrue(result.isSuccess)
        assertEquals(base, result.getOrThrow())
    }

    @Test
    fun `a file with no model-overrides entries reproduces the base catalog unchanged`() = runTest {
        val base = listOf(descriptor("model-a", CapabilityRung.TWO))
        val source = YamlModelDescriptorSource(file = tempFile("model-overrides: {}"), base = { base })

        val result = source.load()

        assertTrue(result.isSuccess)
        assertEquals(base, result.getOrThrow())
    }

    // ── Overrides layer over base ──────────────────────────────────────────────

    @Test
    fun `a rung override re-rungs one model without touching the rest`() = runTest {
        val base = listOf(
            descriptor("model-a", CapabilityRung.ONE),
            descriptor("model-b", CapabilityRung.TWO),
        )
        val yaml = """
            model-overrides:
              model-a:
                rung: FOUR
        """.trimIndent()
        val source = YamlModelDescriptorSource(file = tempFile(yaml), base = { base })

        val catalog = source.load().getOrThrow()

        assertEquals(CapabilityRung.FOUR, catalog.first { it.modelName == "model-a" }.rung)
        assertEquals(CapabilityRung.TWO, catalog.first { it.modelName == "model-b" }.rung)
    }

    @Test
    fun `cost-per-watt and capabilities overrides apply alongside rung`() = runTest {
        val base = listOf(descriptor("model-a", CapabilityRung.ONE))
        val yaml = """
            model-overrides:
              model-a:
                rung: TWO
                cost-per-watt: 0.05
                capabilities: [TOOL_CALLING]
        """.trimIndent()
        val source = YamlModelDescriptorSource(file = tempFile(yaml), base = { base })

        val overridden = source.load().getOrThrow().single()

        assertEquals(CapabilityRung.TWO, overridden.rung)
        assertEquals(0.05, overridden.costPerWatt)
        assertEquals(setOf(ProviderCapability.TOOL_CALLING), overridden.capabilities)
    }

    @Test
    fun `an override omitting a field leaves that field at its base value`() = runTest {
        val base = listOf(
            ModelDescriptor(
                modelName = "model-a",
                providerId = AIProvider_Anthropic.id,
                capabilities = setOf(ProviderCapability.WORLD_KNOWLEDGE),
                reasoning = RelativeReasoning.NORMAL,
                maxContextTokens = 200_000,
                supportedInputs = SupportedInputs.TEXT,
                costPerWatt = 0.02,
                rung = CapabilityRung.ONE,
            ),
        )
        val yaml = """
            model-overrides:
              model-a:
                rung: THREE
        """.trimIndent()
        val source = YamlModelDescriptorSource(file = tempFile(yaml), base = { base })

        val overridden = source.load().getOrThrow().single()

        assertEquals(CapabilityRung.THREE, overridden.rung)
        assertEquals(0.02, overridden.costPerWatt)
        assertEquals(setOf(ProviderCapability.WORLD_KNOWLEDGE), overridden.capabilities)
    }

    // ── Malformed input fails without touching the registry (via Result) ──────

    @Test
    fun `an unknown rung name surfaces a failure`() = runTest {
        val base = listOf(descriptor("model-a"))
        val yaml = """
            model-overrides:
              model-a:
                rung: NINE
        """.trimIndent()
        val source = YamlModelDescriptorSource(file = tempFile(yaml), base = { base })

        val result = source.load()

        assertTrue(result.isFailure)
    }

    @Test
    fun `an override naming a model outside the base catalog surfaces a failure`() = runTest {
        val base = listOf(descriptor("model-a"))
        val yaml = """
            model-overrides:
              nonexistent-model:
                rung: TWO
        """.trimIndent()
        val source = YamlModelDescriptorSource(file = tempFile(yaml), base = { base })

        val result = source.load()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("nonexistent-model") == true)
    }

    @Test
    fun `malformed yaml surfaces a failure rather than throwing`() = runTest {
        val base = listOf(descriptor("model-a"))
        val source = YamlModelDescriptorSource(
            file = tempFile("model-overrides: [this, is, not, a, map]"),
            base = { base },
        )

        val result = source.load()

        assertTrue(result.isFailure)
    }

    // ── Integration with the registry ──────────────────────────────────────────

    @Test
    fun `refresh through a registry picks up a re-rung with no recompile`() = runTest {
        val base = listOf(descriptor("model-a", CapabilityRung.ONE))
        val file = tempFile(
            """
            model-overrides:
              model-a:
                rung: FOUR
            """.trimIndent(),
        )
        val registry = InMemoryModelDescriptorRegistry(
            seed = base,
            source = YamlModelDescriptorSource(file = file, base = { base }),
        )

        assertEquals(CapabilityRung.ONE, registry.descriptorFor("model-a")?.rung)

        assertTrue(registry.refresh().isSuccess)

        assertEquals(CapabilityRung.FOUR, registry.descriptorFor("model-a")?.rung)
    }
}
