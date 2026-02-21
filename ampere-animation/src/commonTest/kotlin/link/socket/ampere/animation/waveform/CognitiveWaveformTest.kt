package link.socket.ampere.animation.waveform

import link.socket.ampere.animation.agent.AgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentLayoutOrientation
import link.socket.ampere.animation.agent.AgentVisualState
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.flow.FlowLayer
import link.socket.ampere.animation.math.Vector3
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.substrate.Vector2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CognitiveWaveformTest {

    private fun createSubstrate(width: Int = 20, height: Int = 15, density: Float = 0.3f): SubstrateState {
        return SubstrateState.create(width, height, density)
    }

    private fun createAgentLayer(width: Int = 20, height: Int = 15): AgentLayer {
        return AgentLayer(width, height, AgentLayoutOrientation.CUSTOM)
    }

    @Test
    fun `idle waveform shows gentle base height variation`() {
        val waveform = CognitiveWaveform(gridWidth = 20, gridDepth = 15)
        val substrate = createSubstrate(density = 0.5f)
        val agents = createAgentLayer()

        // Update with large dt to converge quickly
        waveform.update(substrate, agents, null, dt = 1f)

        // All heights should be positive (base from substrate density)
        var hasPositiveHeight = false
        for (gz in 0 until waveform.gridDepth) {
            for (gx in 0 until waveform.gridWidth) {
                val h = waveform.heightAt(gx, gz)
                assertTrue(h >= 0f, "height at ($gx, $gz) should be >= 0, got $h")
                if (h > 0f) hasPositiveHeight = true
            }
        }
        assertTrue(hasPositiveHeight, "at least some heights should be positive from substrate density")
    }

    @Test
    fun `agent creates visible peak above surrounding terrain`() {
        val waveform = CognitiveWaveform(gridWidth = 20, gridDepth = 15, worldWidth = 20f, worldDepth = 15f)
        val substrate = createSubstrate(density = 0.1f)
        val agents = createAgentLayer()

        // Place a processing agent near the center of world space
        agents.addAgent(AgentVisualState(
            id = "agent-1",
            name = "Spark",
            role = "reasoning",
            position = Vector2(10f, 7.5f), // Center of 20x15 world
            state = AgentActivityState.PROCESSING,
            cognitivePhase = CognitivePhase.EXECUTE
        ))

        // Converge heights
        waveform.update(substrate, agents, null, dt = 1f)

        // The grid point closest to the agent center should be a peak
        val centerGx = 10  // maps to worldX ~10
        val centerGz = 7   // maps to worldZ ~7
        val peakHeight = waveform.heightAt(centerGx, centerGz)

        // Edge height should be lower
        val edgeHeight = waveform.heightAt(0, 0)

        assertTrue(
            peakHeight > edgeHeight,
            "peak at agent ($peakHeight) should be taller than edge ($edgeHeight)"
        )
    }

    @Test
    fun `heightAt returns 0 for out-of-bounds coordinates`() {
        val waveform = CognitiveWaveform(gridWidth = 10, gridDepth = 10)
        assertEquals(0f, waveform.heightAt(-1, 5))
        assertEquals(0f, waveform.heightAt(5, -1))
        assertEquals(0f, waveform.heightAt(10, 5))
        assertEquals(0f, waveform.heightAt(5, 10))
    }

    @Test
    fun `normalAt returns upward-facing normal for flat surface`() {
        val waveform = CognitiveWaveform(gridWidth = 10, gridDepth = 10)
        val substrate = createSubstrate(width = 10, height = 10, density = 0.5f)
        val agents = createAgentLayer(width = 10, height = 10)

        // Uniform density -> flat surface
        waveform.update(substrate, agents, null, dt = 1f)

        // Interior normal should be approximately (0, 1, 0) for flat surface
        val normal = waveform.normalAt(5, 5)
        assertTrue(
            normal.y > 0.9f,
            "flat surface normal Y should be close to 1.0, got ${normal.y}"
        )
        assertTrue(
            abs(normal.x) < 0.15f,
            "flat surface normal X should be close to 0, got ${normal.x}"
        )
    }

    @Test
    fun `worldPosition maps grid coordinates to world space`() {
        val waveform = CognitiveWaveform(
            gridWidth = 20, gridDepth = 10,
            worldWidth = 20f, worldDepth = 10f
        )

        // Center of grid should map to world origin (0, y, 0)
        val center = waveform.worldPosition(10, 5)
        assertTrue(
            abs(center.x) < 1f,
            "center world X should be near 0, got ${center.x}"
        )
        assertTrue(
            abs(center.z) < 1f,
            "center world Z should be near 0, got ${center.z}"
        )

        // Corner should map to negative edge
        val corner = waveform.worldPosition(0, 0)
        assertTrue(corner.x < 0f, "left edge should be negative X")
        assertTrue(corner.z < 0f, "top edge should be negative Z")
    }

    @Test
    fun `temporal smoothing lerps toward target`() {
        val waveform = CognitiveWaveform(gridWidth = 10, gridDepth = 10)
        val lowSubstrate = createSubstrate(width = 10, height = 10, density = 0.1f)
        val highSubstrate = createSubstrate(width = 10, height = 10, density = 0.9f)
        val agents = createAgentLayer(width = 10, height = 10)

        // Start with low density
        waveform.update(lowSubstrate, agents, null, dt = 1f)
        val lowHeight = waveform.heightAt(5, 5)

        // Switch to high density with small dt (partial convergence)
        waveform.update(highSubstrate, agents, null, dt = 0.05f)
        val partialHeight = waveform.heightAt(5, 5)

        // After full convergence
        for (i in 0..20) {
            waveform.update(highSubstrate, agents, null, dt = 0.5f)
        }
        val convergedHeight = waveform.heightAt(5, 5)

        assertTrue(
            partialHeight > lowHeight,
            "partial lerp ($partialHeight) should be above low ($lowHeight)"
        )
        assertTrue(
            convergedHeight > partialHeight,
            "converged ($convergedHeight) should be above partial ($partialHeight)"
        )
    }

    @Test
    fun `flow ridge raises height between connected agents`() {
        val waveform = CognitiveWaveform(gridWidth = 20, gridDepth = 15, worldWidth = 20f, worldDepth = 15f)
        val substrate = createSubstrate(density = 0.1f)
        val agents = createAgentLayer()
        val flow = FlowLayer(20, 15)

        // Place two agents
        val agent1 = AgentVisualState(
            id = "a1", name = "A1", role = "r",
            position = Vector2(5f, 7.5f),
            state = AgentActivityState.PROCESSING,
            cognitivePhase = CognitivePhase.EXECUTE
        )
        val agent2 = AgentVisualState(
            id = "a2", name = "A2", role = "r",
            position = Vector2(15f, 7.5f),
            state = AgentActivityState.PROCESSING,
            cognitivePhase = CognitivePhase.EXECUTE
        )
        agents.addAgent(agent1)
        agents.addAgent(agent2)

        // First: no flow connection
        waveform.update(substrate, agents, null, dt = 1f)
        // Midpoint between agents
        val midGx = 10
        val midGz = 7
        val heightWithoutFlow = waveform.heightAt(midGx, midGz)

        // Now add an active flow connection
        flow.createConnection("a1", "a2", agent1.position, agent2.position)
        flow.startHandoff("a1", "a2")
        // Advance the flow to mid-transmission
        for (i in 0..10) {
            flow.update(0.1f, transmissionSpeed = 1f)
        }

        // Reset waveform and recalculate with flow
        val waveform2 = CognitiveWaveform(gridWidth = 20, gridDepth = 15, worldWidth = 20f, worldDepth = 15f)
        waveform2.update(substrate, agents, flow, dt = 1f)
        val heightWithFlow = waveform2.heightAt(midGx, midGz)

        assertTrue(
            heightWithFlow > heightWithoutFlow,
            "ridge from flow ($heightWithFlow) should raise midpoint above no-flow ($heightWithoutFlow)"
        )
    }

    @Test
    fun `processing agent creates taller peak than idle agent`() {
        val waveform1 = CognitiveWaveform(gridWidth = 20, gridDepth = 15, worldWidth = 20f, worldDepth = 15f)
        val waveform2 = CognitiveWaveform(gridWidth = 20, gridDepth = 15, worldWidth = 20f, worldDepth = 15f)
        val substrate = createSubstrate(density = 0.1f)

        val agentsIdle = createAgentLayer()
        agentsIdle.addAgent(AgentVisualState(
            id = "idle", name = "Idle", role = "r",
            position = Vector2(10f, 7.5f),
            state = AgentActivityState.IDLE
        ))

        val agentsProcessing = createAgentLayer()
        agentsProcessing.addAgent(AgentVisualState(
            id = "proc", name = "Proc", role = "r",
            position = Vector2(10f, 7.5f),
            state = AgentActivityState.PROCESSING
        ))

        waveform1.update(substrate, agentsIdle, null, dt = 1f)
        waveform2.update(substrate, agentsProcessing, null, dt = 1f)

        val idlePeak = waveform1.heightAt(10, 7)
        val processingPeak = waveform2.heightAt(10, 7)

        assertTrue(
            processingPeak > idlePeak,
            "PROCESSING peak ($processingPeak) should be taller than IDLE ($idlePeak)"
        )
    }
}
