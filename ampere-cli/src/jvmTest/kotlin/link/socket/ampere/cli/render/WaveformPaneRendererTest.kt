package link.socket.ampere.cli.render

import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.render.AsciiCell
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector2
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformPaneRendererTest {

    private val agentLayer = AgentLayer(80, 24, AgentLayoutOrientation.CUSTOM)

    private val renderer = WaveformPaneRenderer(
        agentLayer = agentLayer
    )

    @Test
    fun `render returns correct number of lines`() {
        val width = 40
        val height = 20
        val substrate = SubstrateState.create(width, height, baseDensity = 0.3f)
        renderer.update(substrate, flow = null, dt = 0.033f)

        val lines = renderer.render(width, height)

        assertEquals(height, lines.size, "Expected $height lines")
    }

    @Test
    fun `render returns lines with correct visible width`() {
        val width = 30
        val height = 15
        val substrate = SubstrateState.create(width, height, baseDensity = 0.3f)
        renderer.update(substrate, flow = null, dt = 0.033f)

        val lines = renderer.render(width, height)

        for ((idx, line) in lines.withIndex()) {
            // Strip ANSI escape sequences to get visible width
            val visible = line.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
            assertEquals(width, visible.length, "Line $idx visible width should be $width, was ${visible.length}")
        }
    }

    @Test
    fun `render with agents produces non-empty content`() {
        val width = 40
        val height = 20
        val substrate = SubstrateState.create(width, height, baseDensity = 0.5f)

        agentLayer.addAgent(
            AgentVisualState(
                id = "agent-1",
                name = "Test Agent",
                role = "worker",
                position = Vector2(10f, 7f),
                position3D = Vector3(0f, 0f, 0f),
                state = AgentActivityState.ACTIVE,
                cognitivePhase = CognitivePhase.EXECUTE
            )
        )

        renderer.update(substrate, flow = null, dt = 0.1f)
        val lines = renderer.render(width, height)

        // With an active agent creating peaks, at least some lines should have non-space characters
        val hasContent = lines.any { line ->
            val visible = line.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
            visible.any { it != ' ' }
        }
        assertTrue(hasContent, "Expected non-empty waveform content with an active agent")
    }

    @Test
    fun `render without substrate produces valid output`() {
        val width = 30
        val height = 15

        // Don't call update() — renderer should handle missing substrate gracefully
        val lines = renderer.render(width, height)

        assertEquals(height, lines.size, "Should produce $height lines even without substrate update")
    }

    @Test
    fun `cellsToLines produces correct dimensions`() {
        val width = 10
        val height = 5
        val cells = Array(height) { Array(width) { AsciiCell(char = '.', fgColor = 240) } }

        val lines = WaveformPaneRenderer.cellsToLines(cells, width, height)

        assertEquals(height, lines.size)
        for (line in lines) {
            val visible = line.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
            assertEquals(width, visible.length)
        }
    }

    @Test
    fun `cellsToLines preserves characters from cells`() {
        val cells = Array(1) {
            arrayOf(
                AsciiCell(char = '#', fgColor = 196),
                AsciiCell(char = '.', fgColor = 240),
                AsciiCell(char = '@', fgColor = 231)
            )
        }

        val lines = WaveformPaneRenderer.cellsToLines(cells, 3, 1)
        val visible = lines[0].replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
        assertEquals("#.@", visible)
    }

    @Test
    fun `cellsToLines includes ANSI color codes`() {
        val cells = Array(1) {
            arrayOf(AsciiCell(char = '*', fgColor = 196, bold = true))
        }

        val lines = WaveformPaneRenderer.cellsToLines(cells, 1, 1)
        assertTrue(lines[0].contains("\u001B["), "Expected ANSI escape codes")
        assertTrue(lines[0].contains("196"), "Expected color code 196")
    }

    @Test
    fun `cellsToLines pads short rows`() {
        // Grid has 3 rows but we ask for 5 — should pad with spaces
        val cells = Array(3) { Array(5) { AsciiCell(char = '.', fgColor = 240) } }
        val lines = WaveformPaneRenderer.cellsToLines(cells, 5, 5)

        assertEquals(5, lines.size)
        // Last 2 lines should be empty spaces
        val last = lines[4].replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
        assertEquals("     ", last)
    }

    @Test
    fun `queued cognitive events are emitted after runtime initialization`() {
        val width = 20
        val height = 10
        val substrate = SubstrateState.create(width, height, baseDensity = 0.3f)

        agentLayer.addAgent(
            AgentVisualState(
                id = "test-agent",
                name = "Test",
                role = "worker",
                position = Vector2(5f, 5f),
                position3D = Vector3(0f, 0f, 0f),
                state = AgentActivityState.PROCESSING,
                cognitivePhase = CognitivePhase.PLAN
            )
        )

        // Fire an emitter effect
        renderer.onCognitiveEvent(
            link.socket.phosphor.bridge.CognitiveEvent.SparkReceived("test-agent"),
            Vector3(0f, 0f, 0f)
        )

        // Runtime is not initialized before first render; event should be buffered.
        assertEquals(0, renderer.activeEmitterCount())

        renderer.update(substrate, flow = null, dt = 0.033f)
        val lines = renderer.render(width, height)
        assertEquals(height, lines.size)
        assertTrue(renderer.activeEmitterCount() > 0, "Emitter should activate once runtime is initialized")
    }

    @Test
    fun `render advances runtime frame index`() {
        val width = 24
        val height = 12
        val substrate = SubstrateState.create(width, height, baseDensity = 0.25f)

        renderer.update(substrate, flow = null, dt = 0.016f)
        renderer.render(width, height)
        val firstFrame = requireNotNull(renderer.runtimeFrameIndex())

        renderer.update(substrate, flow = null, dt = 0.016f)
        renderer.render(width, height)
        val secondFrame = requireNotNull(renderer.runtimeFrameIndex())

        assertTrue(secondFrame > firstFrame, "Runtime frame index should advance after successive renders")
    }
}
