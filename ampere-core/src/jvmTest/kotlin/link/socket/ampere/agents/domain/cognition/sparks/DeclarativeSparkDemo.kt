package link.socket.ampere.agents.domain.cognition.sparks

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.llm.LlmProvider

/**
 * Verbose demo of the AMPR-161 declarative PhaseSpark surface. Designed to be
 * run from `scripts/demo-declarative-sparks.sh`: writes a human-readable report
 * to `ampere-core/build/declarative-sparks-demo.txt` that the script tails to
 * stdout.
 *
 * Not a behavioral assertion — see [DeclarativeSparkIntegrationTest] for that.
 */
class DeclarativeSparkDemo {

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("custom provider only")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1
        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    private class RecordingSparkAgent(
        agentId: String,
        provider: LlmProvider,
    ) : SparkBasedAgent<CodeState>(
        agentId = agentId,
        cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
        initialState = CodeState.blank,
        _aiConfiguration = FakeAIConfiguration(),
        _llmProvider = provider,
    ) {
        val applied = CopyOnWriteArrayList<String>()
        val removed = CopyOnWriteArrayList<String>()

        override fun onSparkApplied(spark: Spark) {
            applied += spark.name
        }

        override fun onSparkRemoved(previousSpark: Spark?) {
            removed += previousSpark?.name ?: "Unknown"
        }
    }

    @Test
    fun `demo declarative spark behavior`() = runTest {
        val outDir = File("build").apply { mkdirs() }
        val report = File(outDir, "declarative-sparks-demo.txt")
        val out = StringBuilder()

        out.appendLine("=".repeat(72))
        out.appendLine("AMPR-161 — Declarative PhaseSpark Demo")
        out.appendLine("=".repeat(72))

        val library = DefaultPhaseSparkLibrary.load()
        out.appendLine()
        out.appendLine("[1] Loaded bundled .spark.md fixtures:")
        for (spark in library.all().filterIsInstance<DeclarativePhaseSpark>()) {
            out.appendLine(
                "    - id=${spark.sparkId.padEnd(20)} phase=${spark.phase} name=\"${spark.displayName}\"",
            )
        }

        val selectionText = "draft a recipe for risotto"
        val selection = library.selectFor(
            SparkSelectionContext(phase = CognitivePhase.PLAN, text = selectionText),
        )
        out.appendLine()
        out.appendLine("[2] library.selectFor(phase=PLAN, text=\"$selectionText\"):")
        for (spark in selection.filterIsInstance<DeclarativePhaseSpark>()) {
            out.appendLine("    -> ${spark.name}  (whenToUse=\"${spark.whenToUse}\")")
        }

        // Run one Plan phase with the spike flag OFF to show baseline.
        val flagOffPrompts = CopyOnWriteArrayList<String>()
        val flagOffAgent = RecordingSparkAgent(
            agentId = "demo-flag-off",
            provider = { p -> flagOffPrompts += p; "ok" },
        )
        flagOffAgent.setPhaseSparkLibrary(library)
        val flagOffManager = PhaseSparkManager.internalCreate(
            agent = flagOffAgent,
            enabled = true,
            library = library,
        )

        flagOffManager.withPhase(
            phase = CognitivePhase.PLAN,
            selectionContext = SparkSelectionContext(
                phase = CognitivePhase.PLAN,
                text = selectionText,
            ),
        ) {
            flagOffAgent.callLLM("plan the work")
        }

        out.appendLine()
        out.appendLine("[3] Spike flag OFF — Plan phase entry:")
        out.appendLine("    applied sparks: ${flagOffAgent.applied}")
        out.appendLine("    removed sparks: ${flagOffAgent.removed}")
        out.appendLine(
            "    LLM payload contains cooking-domain body? " +
                flagOffPrompts.any { it.contains("Domain Context: Cooking") },
        )

        // Run one Plan phase with the spike flag ON to show the library kicking in.
        val flagOnPrompts = CopyOnWriteArrayList<String>()
        val flagOnAgent = RecordingSparkAgent(
            agentId = "demo-flag-on",
            provider = { p -> flagOnPrompts += p; "ok" },
        )
        flagOnAgent.setPhaseSparkLibrary(library)
        val flagOnManager = PhaseSparkManager.internalCreate(
            agent = flagOnAgent,
            enabled = true,
            library = library,
        )

        try {
            AmpereSpikeFlags.declarativeSparksEnabled = true
            flagOnManager.withPhase(
                phase = CognitivePhase.PLAN,
                selectionContext = SparkSelectionContext(
                    phase = CognitivePhase.PLAN,
                    text = selectionText,
                ),
            ) {
                flagOnAgent.callLLM("plan the work")
            }
        } finally {
            AmpereSpikeFlags.declarativeSparksEnabled = false
        }

        out.appendLine()
        out.appendLine("[4] Spike flag ON  — Plan phase entry:")
        out.appendLine("    applied sparks: ${flagOnAgent.applied}")
        out.appendLine("    removed sparks: ${flagOnAgent.removed}")
        out.appendLine(
            "    LLM payload contains cooking-domain body? " +
                flagOnPrompts.any { it.contains("Domain Context: Cooking") },
        )

        out.appendLine()
        out.appendLine("[5] LLM payload captured with flag ON (last 50 lines of system prompt):")
        out.appendLine("-".repeat(72))
        val payload = flagOnPrompts.firstOrNull().orEmpty()
        val systemBlock = payload
            .substringAfter("System: ", "")
            .substringBefore("\n\nUser: ", payload)
        systemBlock.lines().takeLast(50).forEach { out.appendLine("    $it") }
        out.appendLine("-".repeat(72))

        out.appendLine()
        out.appendLine("Demo report written to: ${report.absolutePath}")
        report.writeText(out.toString())
    }
}
