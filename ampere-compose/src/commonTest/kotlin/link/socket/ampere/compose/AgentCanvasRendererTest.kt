package link.socket.ampere.compose

import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentCanvasRendererTest {

    // --- depthFactor ---

    @Test
    fun depthFactor_zero_depth_returns_one() {
        assertEquals(1f, AgentCanvasRenderer.depthFactor(0f))
    }

    @Test
    fun depthFactor_one_depth_returns_zero() {
        assertEquals(0f, AgentCanvasRenderer.depthFactor(1f))
    }

    @Test
    fun depthFactor_half_depth_returns_half() {
        assertEquals(0.5f, AgentCanvasRenderer.depthFactor(0.5f))
    }

    @Test
    fun depthFactor_negative_depth_clamped_to_one() {
        assertEquals(1f, AgentCanvasRenderer.depthFactor(-0.5f))
    }

    @Test
    fun depthFactor_above_one_clamped_to_zero() {
        assertEquals(0f, AgentCanvasRenderer.depthFactor(1.5f))
    }

    // --- nodeRadius ---

    @Test
    fun nodeRadius_full_depth_factor_returns_full_base() {
        assertEquals(10f, AgentCanvasRenderer.nodeRadius(10f, 1f))
    }

    @Test
    fun nodeRadius_zero_depth_factor_returns_half_base() {
        assertEquals(5f, AgentCanvasRenderer.nodeRadius(10f, 0f))
    }

    @Test
    fun nodeRadius_half_depth_factor_returns_three_quarter_base() {
        assertEquals(7.5f, AgentCanvasRenderer.nodeRadius(10f, 0.5f))
    }

    // --- ringAlpha ---

    @Test
    fun ringAlpha_active_phase_full_depth_returns_08() {
        assertEquals(0.8f, AgentCanvasRenderer.ringAlpha(1f, CognitivePhase.EXECUTE))
    }

    @Test
    fun ringAlpha_active_phase_zero_depth_returns_03() {
        assertEquals(0.3f, AgentCanvasRenderer.ringAlpha(0f, CognitivePhase.PERCEIVE))
    }

    @Test
    fun ringAlpha_none_phase_full_depth_returns_02() {
        assertEquals(0.2f, AgentCanvasRenderer.ringAlpha(1f, CognitivePhase.NONE))
    }

    @Test
    fun ringAlpha_none_phase_zero_depth_returns_01() {
        assertEquals(0.1f, AgentCanvasRenderer.ringAlpha(0f, CognitivePhase.NONE))
    }

    @Test
    fun ringAlpha_active_always_brighter_than_none() {
        for (df in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val active = AgentCanvasRenderer.ringAlpha(df, CognitivePhase.PLAN)
            val none = AgentCanvasRenderer.ringAlpha(df, CognitivePhase.NONE)
            assertTrue(active > none, "Active phase ring should be brighter than NONE at depthFactor=$df")
        }
    }

    // --- fillAlpha ---

    @Test
    fun fillAlpha_full_depth_returns_09() {
        assertEquals(0.9f, AgentCanvasRenderer.fillAlpha(1f))
    }

    @Test
    fun fillAlpha_zero_depth_returns_04() {
        assertEquals(0.4f, AgentCanvasRenderer.fillAlpha(0f))
    }

    // --- shimmerAlpha ---

    @Test
    fun shimmerAlpha_zero_phase_returns_025() {
        assertApprox(0.25f, AgentCanvasRenderer.shimmerAlpha(0f))
    }

    @Test
    fun shimmerAlpha_quarter_phase_returns_05() {
        assertApprox(0.5f, AgentCanvasRenderer.shimmerAlpha(0.25f))
    }

    @Test
    fun shimmerAlpha_half_phase_returns_025() {
        assertApprox(0.25f, AgentCanvasRenderer.shimmerAlpha(0.5f))
    }

    @Test
    fun shimmerAlpha_three_quarter_phase_returns_0() {
        assertApprox(0f, AgentCanvasRenderer.shimmerAlpha(0.75f))
    }

    @Test
    fun shimmerAlpha_range_always_between_0_and_05() {
        for (i in 0..100) {
            val phase = i / 100f
            val alpha = AgentCanvasRenderer.shimmerAlpha(phase)
            assertTrue(alpha >= -0.001f, "shimmerAlpha($phase) = $alpha should be >= 0")
            assertTrue(alpha <= 0.501f, "shimmerAlpha($phase) = $alpha should be <= 0.5")
        }
    }

    // --- colorForState ---

    @Test
    fun colorForState_idle_returns_agentIdle() {
        assertEquals(CognitivePalette.agentIdle, AgentCanvasRenderer.colorForState(AgentActivityState.IDLE))
    }

    @Test
    fun colorForState_active_returns_agentActive() {
        assertEquals(CognitivePalette.agentActive, AgentCanvasRenderer.colorForState(AgentActivityState.ACTIVE))
    }

    @Test
    fun colorForState_processing_returns_agentProcessing() {
        assertEquals(
            CognitivePalette.agentProcessing,
            AgentCanvasRenderer.colorForState(AgentActivityState.PROCESSING),
        )
    }

    @Test
    fun colorForState_complete_returns_agentComplete() {
        assertEquals(
            CognitivePalette.agentComplete,
            AgentCanvasRenderer.colorForState(AgentActivityState.COMPLETE),
        )
    }

    @Test
    fun colorForState_spawning_returns_agentIdle() {
        assertEquals(CognitivePalette.agentIdle, AgentCanvasRenderer.colorForState(AgentActivityState.SPAWNING))
    }

    // --- helpers ---

    private fun assertApprox(expected: Float, actual: Float, tolerance: Float = 0.001f) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "Expected $expected but was $actual (tolerance $tolerance)",
        )
    }
}
