package link.socket.ampere.compose

import androidx.compose.ui.graphics.Color
import link.socket.phosphor.color.CognitiveColorModel
import link.socket.phosphor.color.FlowColorState
import link.socket.phosphor.color.ParticleColorKind
import link.socket.phosphor.renderer.ComposeColor
import link.socket.phosphor.renderer.ComposeColorAdapter
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase
import kotlin.test.Test
import kotlin.test.assertEquals

class CognitivePaletteTest {
    private fun assertComposeColorEquals(expected: ComposeColor, actual: Color) {
        assertEquals(expected.red / 255f, actual.red, 0.0001f)
        assertEquals(expected.green / 255f, actual.green, 0.0001f)
        assertEquals(expected.blue / 255f, actual.blue, 0.0001f)
        assertEquals(expected.alpha, actual.alpha, 0.0001f)
    }

    @Test
    fun forPhase_perceive_returns_cornflower_blue() {
        assertEquals(CognitivePalette.perceive, CognitivePalette.forPhase(CognitivePhase.PERCEIVE))
    }

    @Test
    fun forPhase_recall_returns_goldenrod() {
        assertEquals(CognitivePalette.recall, CognitivePalette.forPhase(CognitivePhase.RECALL))
    }

    @Test
    fun forPhase_plan_returns_purple() {
        assertEquals(CognitivePalette.plan, CognitivePalette.forPhase(CognitivePhase.PLAN))
    }

    @Test
    fun forPhase_execute_returns_orange() {
        assertEquals(CognitivePalette.execute, CognitivePalette.forPhase(CognitivePhase.EXECUTE))
    }

    @Test
    fun forPhase_evaluate_returns_aquamarine() {
        assertEquals(CognitivePalette.evaluate, CognitivePalette.forPhase(CognitivePhase.EVALUATE))
    }

    @Test
    fun forPhase_loop_returns_slate_gray() {
        assertEquals(CognitivePalette.loop, CognitivePalette.forPhase(CognitivePhase.LOOP))
    }

    @Test
    fun forPhase_none_returns_agent_idle() {
        assertEquals(CognitivePalette.agentIdle, CognitivePalette.forPhase(CognitivePhase.NONE))
    }

    @Test
    fun forDensity_low_returns_dim() {
        assertEquals(CognitivePalette.substrateDim, CognitivePalette.forDensity(0.0f))
        assertEquals(CognitivePalette.substrateDim, CognitivePalette.forDensity(0.1f))
        assertEquals(CognitivePalette.substrateDim, CognitivePalette.forDensity(0.29f))
    }

    @Test
    fun forDensity_mid_returns_teal() {
        assertEquals(CognitivePalette.substrateMid, CognitivePalette.forDensity(0.3f))
        assertEquals(CognitivePalette.substrateMid, CognitivePalette.forDensity(0.45f))
        assertEquals(CognitivePalette.substrateMid, CognitivePalette.forDensity(0.59f))
    }

    @Test
    fun forDensity_high_returns_bright() {
        assertEquals(CognitivePalette.substrateBright, CognitivePalette.forDensity(0.6f))
        assertEquals(CognitivePalette.substrateBright, CognitivePalette.forDensity(0.8f))
        assertEquals(CognitivePalette.substrateBright, CognitivePalette.forDensity(1.0f))
    }

    @Test
    fun each_phase_maps_to_distinct_color() {
        val phases = CognitivePhase.entries.filter { it != CognitivePhase.NONE }
        val colors = phases.map { CognitivePalette.forPhase(it) }.toSet()
        assertEquals(phases.size, colors.size, "Each cognitive phase should have a distinct color")
    }

    @Test
    fun palette_colors_are_fully_opaque() {
        val colors = listOf(
            CognitivePalette.substrateDim,
            CognitivePalette.substrateMid,
            CognitivePalette.substrateBright,
            CognitivePalette.agentIdle,
            CognitivePalette.agentActive,
            CognitivePalette.agentProcessing,
            CognitivePalette.agentComplete,
            CognitivePalette.perceive,
            CognitivePalette.recall,
            CognitivePalette.plan,
            CognitivePalette.execute,
            CognitivePalette.evaluate,
            CognitivePalette.loop,
        )
        for (color in colors) {
            assertEquals(1.0f, color.alpha, "Palette colors should be fully opaque")
        }
    }

    @Test
    fun `palette colors align with phosphor cognitive color model`() {
        val model = CognitiveColorModel
        val adapter = ComposeColorAdapter()

        assertComposeColorEquals(
            adapter.adapt(model.agentActivityColors.getValue(AgentActivityState.ACTIVE)),
            CognitivePalette.agentActive
        )
        assertComposeColorEquals(
            adapter.adapt(model.flowStateColors.getValue(FlowColorState.ACTIVATING)),
            CognitivePalette.flowActive
        )
        assertComposeColorEquals(
            adapter.adapt(model.particleColors.getValue(ParticleColorKind.SPARK)),
            CognitivePalette.sparkAccent
        )
        assertComposeColorEquals(
            adapter.adapt(model.phaseColorFor(CognitivePhase.PLAN)),
            CognitivePalette.plan
        )
    }
}
