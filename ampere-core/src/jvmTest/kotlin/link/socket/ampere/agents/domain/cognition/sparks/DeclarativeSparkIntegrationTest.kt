package link.socket.ampere.agents.domain.cognition.sparks

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.llm.LlmProvider

class DeclarativeSparkIntegrationTest {

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Provider should not be called when custom provider is set")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    /**
     * Records the spark names that flow through the cognitive lifecycle. Bypasses the
     * event-bus pipeline so the assertions don't depend on async observability draining.
     */
    private class RecordingSparkAgent(
        agentId: String,
        provider: LlmProvider,
    ) : SparkBasedAgent(
        agentId = agentId,
        cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
        _aiConfiguration = FakeAIConfiguration(),
        _llmProvider = provider,
    ) {
        val appliedNames = CopyOnWriteArrayList<String>()
        val removedNames = CopyOnWriteArrayList<String>()

        override fun onSparkApplied(spark: Spark) {
            appliedNames += spark.name
        }

        override fun onSparkRemoved(previousSpark: Spark?) {
            removedNames += previousSpark?.name ?: "Unknown"
        }
    }

    @Test
    fun `declarative spark applies, reaches LLM payload, and is removed on phase exit`() = runTest {
        val capturedPrompts = CopyOnWriteArrayList<String>()
        val provider: LlmProvider = { prompt ->
            capturedPrompts += prompt
            "ok"
        }

        val library = DefaultPhaseSparkLibrary.load()

        val agent = RecordingSparkAgent(
            agentId = "declarative-spark-agent",
            provider = provider,
        )
        agent.setPhaseSparkLibrary(library)

        val manager = PhaseSparkManager.internalCreate(
            agent = agent,
            enabled = true,
            library = library,
        )

        try {
            AmpereSpikeFlags.declarativeSparksEnabled = true

            manager.withPhase(
                phase = CognitivePhase.PLAN,
                selectionContext = SparkSelectionContext(
                    phase = CognitivePhase.PLAN,
                    text = "draft a recipe for risotto",
                ),
            ) {
                agent.callLLM("plan the work")
            }
        } finally {
            AmpereSpikeFlags.declarativeSparksEnabled = false
        }

        val cookingMarker = "Domain Context: Cooking"
        assertTrue(
            capturedPrompts.any { it.contains(cookingMarker) },
            "expected at least one captured prompt to contain '$cookingMarker', got: $capturedPrompts",
        )

        assertTrue(
            "Phase:Plan" in agent.appliedNames,
            "expected applied sparks to include built-in Phase:Plan, got: ${agent.appliedNames}",
        )
        assertTrue(
            "PhaseSpark:cooking-domain" in agent.appliedNames,
            "expected applied sparks to include declarative PhaseSpark:cooking-domain, got: ${agent.appliedNames}",
        )

        val phaseAppliedCount = agent.appliedNames.count {
            it == "Phase:Plan" || it == "PhaseSpark:cooking-domain"
        }
        val phaseRemovedCount = agent.removedNames.count {
            it == "Phase:Plan" || it == "PhaseSpark:cooking-domain"
        }
        assertEquals(
            phaseAppliedCount,
            phaseRemovedCount,
            "all applied phase sparks should be removed on exit; " +
                "applied=${agent.appliedNames} removed=${agent.removedNames}",
        )
    }
}
