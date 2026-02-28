package link.socket.ampere.compose

import link.socket.phosphor.signal.CognitivePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformCanvasRendererTest {

    // --- dominantPhaseAt ---

    @Test
    fun dominantPhaseAt_empty_agents_returns_NONE() {
        val result = WaveformCanvasRenderer.dominantPhaseAt(
            worldX = 5f,
            worldZ = 5f,
            agentPositions = emptyList()
        )
        assertEquals(CognitivePhase.NONE, result)
    }

    @Test
    fun dominantPhaseAt_single_agent_returns_its_phase() {
        val agents = listOf(
            Triple(5f, 5f, CognitivePhase.EXECUTE)
        )
        val result = WaveformCanvasRenderer.dominantPhaseAt(
            worldX = 5f,
            worldZ = 5f,
            agentPositions = agents
        )
        assertEquals(CognitivePhase.EXECUTE, result)
    }

    @Test
    fun dominantPhaseAt_returns_nearest_agent_phase() {
        val agents = listOf(
            Triple(0f, 0f, CognitivePhase.PERCEIVE),  // far from query
            Triple(10f, 10f, CognitivePhase.EXECUTE),  // near query
        )
        val result = WaveformCanvasRenderer.dominantPhaseAt(
            worldX = 9f,
            worldZ = 9f,
            agentPositions = agents
        )
        assertEquals(CognitivePhase.EXECUTE, result)
    }

    @Test
    fun dominantPhaseAt_same_phase_agents_accumulate_weight() {
        // Two PLAN agents vs one closer EXECUTE agent — combined weight wins
        val agents = listOf(
            Triple(4f, 4f, CognitivePhase.PLAN),
            Triple(6f, 6f, CognitivePhase.PLAN),
            Triple(5.1f, 5.1f, CognitivePhase.EXECUTE),
        )
        val result = WaveformCanvasRenderer.dominantPhaseAt(
            worldX = 5f,
            worldZ = 5f,
            agentPositions = agents
        )
        assertEquals(CognitivePhase.PLAN, result)
    }

    @Test
    fun dominantPhaseAt_exact_position_dominates() {
        val agents = listOf(
            Triple(0f, 0f, CognitivePhase.RECALL),
            Triple(5f, 5f, CognitivePhase.EVALUATE),
        )
        // Query at exact position of EVALUATE agent
        val result = WaveformCanvasRenderer.dominantPhaseAt(
            worldX = 5f,
            worldZ = 5f,
            agentPositions = agents
        )
        assertEquals(CognitivePhase.EVALUATE, result)
    }

    @Test
    fun dominantPhaseAt_equidistant_agents_with_different_phases() {
        // Two agents equidistant — both get same weight, first one encountered wins
        val agents = listOf(
            Triple(4f, 5f, CognitivePhase.PERCEIVE),
            Triple(6f, 5f, CognitivePhase.EXECUTE),
        )
        val result = WaveformCanvasRenderer.dominantPhaseAt(
            worldX = 5f,
            worldZ = 5f,
            agentPositions = agents
        )
        // Both have identical distance, so both have identical weight.
        // The result depends on map iteration order — just verify it's one of them.
        assertTrue(
            result == CognitivePhase.PERCEIVE || result == CognitivePhase.EXECUTE,
            "Expected either PERCEIVE or EXECUTE for equidistant agents"
        )
    }

    @Test
    fun dominantPhaseAt_very_distant_agents_still_contribute() {
        // Even very far agents contribute some weight (inverse-distance, never zero)
        val agents = listOf(
            Triple(1000f, 1000f, CognitivePhase.LOOP)
        )
        val result = WaveformCanvasRenderer.dominantPhaseAt(
            worldX = 0f,
            worldZ = 0f,
            agentPositions = agents
        )
        assertEquals(CognitivePhase.LOOP, result, "Distant agents should still influence the result")
    }

    @Test
    fun dominantPhaseAt_multiple_agents_same_position() {
        // Three agents at the same spot, two PLAN and one EXECUTE — PLAN should win
        val agents = listOf(
            Triple(3f, 3f, CognitivePhase.PLAN),
            Triple(3f, 3f, CognitivePhase.PLAN),
            Triple(3f, 3f, CognitivePhase.EXECUTE),
        )
        val result = WaveformCanvasRenderer.dominantPhaseAt(
            worldX = 3f,
            worldZ = 3f,
            agentPositions = agents
        )
        assertEquals(CognitivePhase.PLAN, result)
    }
}
