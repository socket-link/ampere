package link.socket.ampere.agents.domain.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.InMemoryProviderDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ProviderCapability
import link.socket.ampere.agents.domain.routing.capability.ProviderDescriptor
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.domain.arc.ArcAgentConfig
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ArcRegistry

class RouteCostReportTest {

    @Test
    fun `default report routes every built-in Arc step to the cheapest provider`() = runTest {
        val report = RouteCostReporter(InMemoryProviderDescriptorRegistry()).report()

        val builtInArcs = ArcRegistry.list()
        assertEquals(builtInArcs.size, report.arcs.size)

        // With no requirement every provider qualifies, so Google (cheapest in
        // the default seed) wins every step at 0.007 USD/W.
        for (arc in report.arcs) {
            assertTrue(arc.steps.isNotEmpty())
            for (step in arc.steps) {
                assertEquals(AIProvider_Google.id, step.chosenProvider)
                assertEquals(0.007, step.estimatedWattCost, absoluteTolerance = 1e-9)
                assertEquals(AIProvider_OpenAI.id, step.runnerUpProvider)
            }
        }

        // Total is deterministic: cheapest rate × number of steps across all Arcs.
        val totalSteps = builtInArcs.sumOf { it.agents.size }
        assertEquals(0.007 * totalSteps, report.totalWattCost, absoluteTolerance = 1e-9)
    }

    @Test
    fun `text-only steps fall to the Free local provider while knowledge steps stay cloud`() = runTest {
        // Anthropic stands in for a local 0W provider that lacks world knowledge.
        val registry = InMemoryProviderDescriptorRegistry(
            seed = listOf(
                ProviderDescriptor(
                    providerId = AIProvider_Anthropic.id,
                    capabilities = emptySet(),
                    reasoning = RelativeReasoning.LOW,
                    maxContextTokens = 8_192,
                    supportedInputs = SupportedInputs.TEXT,
                    cost = CostPolicy.Free,
                ),
                ProviderDescriptor(
                    providerId = AIProvider_Google.id,
                    capabilities = setOf(ProviderCapability.WORLD_KNOWLEDGE),
                    reasoning = RelativeReasoning.HIGH,
                    maxContextTokens = 200_000,
                    supportedInputs = SupportedInputs.TEXT_AND_IMAGE,
                    cost = CostPolicy.Metered,
                    costPerWatt = 0.007,
                ),
            ),
        )

        // A "writer" step needs only text; everything else needs world knowledge.
        val reporter = RouteCostReporter(registry) { _, agent ->
            if (agent.role == "writer") {
                CapabilityRequirement(inputs = SupportedInputs.TEXT)
            } else {
                CapabilityRequirement(required = setOf(ProviderCapability.WORLD_KNOWLEDGE))
            }
        }

        val arc = ArcConfig(
            name = "research-paper",
            agents = listOf(
                ArcAgentConfig(role = "scholar"),
                ArcAgentConfig(role = "writer"),
                ArcAgentConfig(role = "critic"),
            ),
        )
        val report = reporter.report(listOf(arc))

        val steps = report.arcs.single().steps.associateBy { it.stepLabel }
        assertEquals(AIProvider_Anthropic.id, steps.getValue("writer").chosenProvider)
        assertEquals(0.0, steps.getValue("writer").estimatedWattCost, absoluteTolerance = 1e-9)
        assertEquals(AIProvider_Google.id, steps.getValue("scholar").chosenProvider)
        assertEquals(AIProvider_Google.id, steps.getValue("critic").chosenProvider)
    }

    @Test
    fun `steps with no capable provider are reported as unmet`() = runTest {
        val registry = InMemoryProviderDescriptorRegistry(
            seed = listOf(
                ProviderDescriptor(
                    providerId = AIProvider_Google.id,
                    capabilities = emptySet(),
                    reasoning = RelativeReasoning.LOW,
                    maxContextTokens = 8_192,
                    supportedInputs = SupportedInputs.TEXT,
                    cost = CostPolicy.Metered,
                    costPerWatt = 0.007,
                ),
            ),
        )
        val reporter = RouteCostReporter(registry) { _, _ ->
            CapabilityRequirement(required = setOf(ProviderCapability.MULTIMODAL_INPUT))
        }

        val arc = ArcConfig(name = "x", agents = listOf(ArcAgentConfig(role = "scanner")))
        val step = reporter.report(listOf(arc)).arcs.single().steps.single()

        assertTrue(step.unmet)
        assertEquals(0, step.candidateCount)
    }

    @Test
    fun `render produces a deterministic non-empty table`() = runTest {
        val report = RouteCostReporter(InMemoryProviderDescriptorRegistry())
            .report(listOf(ArcConfig(name = "demo", agents = listOf(ArcAgentConfig(role = "pm")))))

        val rendered = report.render()
        assertTrue(rendered.contains("demo"))
        assertTrue(rendered.contains("google"))
        assertTrue(rendered.contains("All Arcs total"))
    }
}
