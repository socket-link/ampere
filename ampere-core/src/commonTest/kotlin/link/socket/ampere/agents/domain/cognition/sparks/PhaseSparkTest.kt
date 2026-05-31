package link.socket.ampere.agents.domain.cognition.sparks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Locks the canonical PROPEL ordering of [CognitivePhase] and the
 * round-trip contract of [PhaseSpark.forPhase].
 *
 * The acronym is load-bearing: if these tests fail, downstream consumers
 * that rely on `enumValues<CognitivePhase>()` producing the cycle in
 * order (animation bridges, telemetry, docs) will silently drift.
 */
class PhaseSparkTest {

    @Test
    fun `CognitivePhase declares the canonical PROPEL ordering`() {
        assertEquals(
            listOf(
                CognitivePhase.PERCEIVE,
                CognitivePhase.RECALL,
                CognitivePhase.OBSERVE,
                CognitivePhase.PLAN,
                CognitivePhase.EXECUTE,
                CognitivePhase.LEARN,
            ),
            CognitivePhase.entries.toList(),
        )
    }

    @Test
    fun `forPhase returns a Spark whose phase matches the input`() {
        CognitivePhase.entries.forEach { phase ->
            val spark = PhaseSpark.forPhase(phase)
            assertEquals(phase, spark.phase, "forPhase($phase) returned a spark for ${spark.phase}")
        }
    }

    @Test
    fun `forPhase returns the canonical singleton for each phase`() {
        assertSame(PhaseSpark.Perceive, PhaseSpark.forPhase(CognitivePhase.PERCEIVE))
        assertSame(PhaseSpark.Recall, PhaseSpark.forPhase(CognitivePhase.RECALL))
        assertSame(PhaseSpark.Observe, PhaseSpark.forPhase(CognitivePhase.OBSERVE))
        assertSame(PhaseSpark.Plan, PhaseSpark.forPhase(CognitivePhase.PLAN))
        assertSame(PhaseSpark.Execute, PhaseSpark.forPhase(CognitivePhase.EXECUTE))
        assertSame(PhaseSpark.Learn, PhaseSpark.forPhase(CognitivePhase.LEARN))
    }

    @Test
    fun `every phase spark carries a non-blank prompt contribution`() {
        CognitivePhase.entries.forEach { phase ->
            val spark = PhaseSpark.forPhase(phase)
            assertTrue(
                spark.promptContribution.isNotBlank(),
                "Phase $phase produced a blank promptContribution",
            )
        }
    }

    @Test
    fun `RECALL spark mentions memory or prior knowledge`() {
        val text = PhaseSpark.Recall.promptContribution.lowercase()
        assertTrue(
            "memory" in text || "prior" in text || "recall" in text,
            "Recall spark should ground the agent in memory/prior context: $text",
        )
    }

    @Test
    fun `OBSERVE spark mentions state monitoring or change detection`() {
        val text = PhaseSpark.Observe.promptContribution.lowercase()
        assertTrue(
            "state" in text || "change" in text || "monitor" in text || "observ" in text,
            "Observe spark should ground the agent in state monitoring: $text",
        )
    }
}
