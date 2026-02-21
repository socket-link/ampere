package link.socket.ampere.animation.waveform

import link.socket.ampere.animation.agent.AgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentLayoutOrientation
import link.socket.ampere.animation.agent.AgentVisualState
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.render.AsciiLuminancePalette
import link.socket.ampere.animation.render.CognitiveColorRamp
import link.socket.ampere.animation.substrate.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PhaseBlenderTest {

    private val blender = PhaseBlender(influenceRadius = 10f)

    private fun createAgentLayer(): AgentLayer {
        return AgentLayer(40, 30, AgentLayoutOrientation.CUSTOM)
    }

    @Test
    fun `returns null when no agents exist`() {
        val agents = createAgentLayer()
        val result = blender.blendedPaletteAt(10f, 10f, agents)
        assertNull(result, "should return null with no agents")
    }

    @Test
    fun `returns null when point is beyond influence radius`() {
        val agents = createAgentLayer()
        agents.addAgent(AgentVisualState(
            id = "a1", name = "A1", role = "r",
            position = Vector2(0f, 0f),
            state = AgentActivityState.ACTIVE,
            cognitivePhase = CognitivePhase.EXECUTE
        ))

        // Point far away from agent at (0,0)
        val result = blender.blendedPaletteAt(100f, 100f, agents)
        assertNull(result, "should return null beyond influence radius")
    }

    @Test
    fun `returns EXECUTE palette near EXECUTE agent`() {
        val agents = createAgentLayer()
        agents.addAgent(AgentVisualState(
            id = "a1", name = "A1", role = "r",
            position = Vector2(10f, 10f),
            state = AgentActivityState.PROCESSING,
            cognitivePhase = CognitivePhase.EXECUTE
        ))

        val result = blender.blendedPaletteAt(10f, 10f, agents)
        assertNotNull(result, "should return palette near agent")
        val (palette, ramp) = result
        assertEquals(AsciiLuminancePalette.EXECUTE, palette)
        assertEquals(CognitiveColorRamp.EXECUTE, ramp)
    }

    @Test
    fun `returns PERCEIVE palette near PERCEIVE agent`() {
        val agents = createAgentLayer()
        agents.addAgent(AgentVisualState(
            id = "a1", name = "A1", role = "r",
            position = Vector2(10f, 10f),
            state = AgentActivityState.ACTIVE,
            cognitivePhase = CognitivePhase.PERCEIVE
        ))

        val result = blender.blendedPaletteAt(10f, 10f, agents)
        assertNotNull(result)
        val (palette, ramp) = result
        assertEquals(AsciiLuminancePalette.PERCEIVE, palette)
        assertEquals(CognitiveColorRamp.PERCEIVE, ramp)
    }

    @Test
    fun `closer agent dominates blend`() {
        val agents = createAgentLayer()

        // EXECUTE agent very close
        agents.addAgent(AgentVisualState(
            id = "a1", name = "A1", role = "r",
            position = Vector2(10f, 10f),
            state = AgentActivityState.PROCESSING,
            cognitivePhase = CognitivePhase.EXECUTE
        ))

        // PERCEIVE agent further away
        agents.addAgent(AgentVisualState(
            id = "a2", name = "A2", role = "r",
            position = Vector2(18f, 10f),
            state = AgentActivityState.ACTIVE,
            cognitivePhase = CognitivePhase.PERCEIVE
        ))

        // Query near the EXECUTE agent
        val result = blender.blendedPaletteAt(10.5f, 10f, agents)
        assertNotNull(result)
        val (palette, _) = result
        assertEquals(
            AsciiLuminancePalette.EXECUTE, palette,
            "closer EXECUTE agent should dominate"
        )
    }

    @Test
    fun `equidistant agents with same phase return that phase`() {
        val agents = createAgentLayer()

        agents.addAgent(AgentVisualState(
            id = "a1", name = "A1", role = "r",
            position = Vector2(5f, 10f),
            state = AgentActivityState.ACTIVE,
            cognitivePhase = CognitivePhase.PLAN
        ))

        agents.addAgent(AgentVisualState(
            id = "a2", name = "A2", role = "r",
            position = Vector2(15f, 10f),
            state = AgentActivityState.ACTIVE,
            cognitivePhase = CognitivePhase.PLAN
        ))

        // Midpoint
        val result = blender.blendedPaletteAt(10f, 10f, agents)
        assertNotNull(result)
        val (palette, _) = result
        assertEquals(AsciiLuminancePalette.PLAN, palette, "both agents are PLAN, result should be PLAN")
    }

    @Test
    fun `paletteForPhase maps all phases`() {
        assertEquals(AsciiLuminancePalette.PERCEIVE, PhaseBlender.paletteForPhase(CognitivePhase.PERCEIVE))
        assertEquals(AsciiLuminancePalette.RECALL, PhaseBlender.paletteForPhase(CognitivePhase.RECALL))
        assertEquals(AsciiLuminancePalette.PLAN, PhaseBlender.paletteForPhase(CognitivePhase.PLAN))
        assertEquals(AsciiLuminancePalette.EXECUTE, PhaseBlender.paletteForPhase(CognitivePhase.EXECUTE))
        assertEquals(AsciiLuminancePalette.EVALUATE, PhaseBlender.paletteForPhase(CognitivePhase.EVALUATE))
        assertEquals(AsciiLuminancePalette.STANDARD, PhaseBlender.paletteForPhase(CognitivePhase.LOOP))
        assertEquals(AsciiLuminancePalette.STANDARD, PhaseBlender.paletteForPhase(CognitivePhase.NONE))
    }
}
