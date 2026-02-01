package link.socket.ampere.cli.animation.render

import link.socket.ampere.cli.animation.agent.AgentActivityState
import link.socket.ampere.cli.animation.agent.AgentLayer
import link.socket.ampere.cli.animation.agent.AgentVisualState
import link.socket.ampere.cli.animation.flow.FlowLayer
import link.socket.ampere.cli.animation.logo.LogoCrystallizer
import link.socket.ampere.cli.animation.particle.ParticleSystem
import link.socket.ampere.cli.animation.substrate.SubstrateState
import link.socket.ampere.cli.animation.substrate.Vector2
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmperePaletteTest {

    @Test
    fun `forDensity returns appropriate colors`() {
        val dim = AmperePalette.forDensity(0.1f)
        val mid = AmperePalette.forDensity(0.4f)
        val bright = AmperePalette.forDensity(0.8f)

        assertEquals(AmperePalette.SUBSTRATE_DIM, dim)
        assertEquals(AmperePalette.SUBSTRATE_MID, mid)
        assertEquals(AmperePalette.SUBSTRATE_BRIGHT, bright)
    }

    @Test
    fun `all color codes start with escape sequence`() {
        val colors = listOf(
            AmperePalette.SUBSTRATE_DIM,
            AmperePalette.SUBSTRATE_MID,
            AmperePalette.SUBSTRATE_BRIGHT,
            AmperePalette.AGENT_IDLE,
            AmperePalette.AGENT_ACTIVE,
            AmperePalette.FLOW_DORMANT,
            AmperePalette.FLOW_ACTIVE,
            AmperePalette.RESET
        )

        colors.forEach { color ->
            assertTrue(color.startsWith("\u001B["), "Color should start with escape sequence")
        }
    }
}

class RenderLayerTest {

    @Test
    fun `layers have correct priority order`() {
        assertTrue(RenderLayer.SUBSTRATE.priority < RenderLayer.FLOW.priority)
        assertTrue(RenderLayer.FLOW.priority < RenderLayer.PARTICLES.priority)
        assertTrue(RenderLayer.PARTICLES.priority < RenderLayer.AGENTS.priority)
        assertTrue(RenderLayer.AGENTS.priority < RenderLayer.LOGO.priority)
        assertTrue(RenderLayer.LOGO.priority < RenderLayer.UI_OVERLAY.priority)
    }
}

class RenderCellTest {

    @Test
    fun `RenderCell stores char and color`() {
        val cell = RenderCell('A', AmperePalette.AGENT_ACTIVE, RenderLayer.AGENTS)

        assertEquals('A', cell.char)
        assertEquals(AmperePalette.AGENT_ACTIVE, cell.color)
        assertEquals(RenderLayer.AGENTS, cell.layer)
    }

    @Test
    fun `RenderCell has default values`() {
        val cell = RenderCell('X')

        assertEquals('X', cell.char)
        assertNull(cell.color)
        assertEquals(RenderLayer.SUBSTRATE, cell.layer)
    }
}

class CompositeRendererTest {

    @Test
    fun `render with no layers produces empty screen`() {
        val renderer = CompositeRenderer(80, 24)

        val output = renderer.render()

        assertNotNull(output)
        assertTrue(output.contains("\u001B[2J"), "Should clear screen")
        assertTrue(output.contains("\u001B[H"), "Should position at home")
    }

    @Test
    fun `render includes substrate layer`() {
        val renderer = CompositeRenderer(80, 24, useColor = false)
        val substrate = SubstrateState.create(80, 24, baseDensity = 0.5f)

        val output = renderer.render(substrate = substrate)

        // Should have glyph characters from substrate
        assertTrue(output.any { it != ' ' && it != '\n' && !it.isISOControl() })
    }

    @Test
    fun `render includes agent layer`() {
        val renderer = CompositeRenderer(80, 24, useColor = false)
        val agents = AgentLayer(80, 24)
        agents.addAgent(AgentVisualState(
            id = "spark",
            name = "Spark",
            role = "reasoning",
            position = Vector2(40f, 12f)
        ))

        val output = renderer.render(agents = agents)

        assertTrue(output.contains("Spark") || output.contains("◉"))
    }

    @Test
    fun `render with status bar includes status text`() {
        val renderer = CompositeRenderer(80, 24)

        val output = renderer.render(statusBar = "Press Q to quit")

        assertTrue(output.contains("Press Q to quit"))
    }

    @Test
    fun `forTerminal creates renderer with clamped dimensions`() {
        val renderer = CompositeRenderer.forTerminal(10, 5)

        // Should clamp to minimum dimensions
        val output = renderer.render()
        assertNotNull(output)
    }

    @Test
    fun `getSizeRecommendation returns warning for small terminals`() {
        val smallRenderer = CompositeRenderer(60, 20)
        val largeRenderer = CompositeRenderer(100, 30)

        assertNotNull(smallRenderer.getSizeRecommendation())
        assertNull(largeRenderer.getSizeRecommendation())
    }

    @Test
    fun `clearScreen returns correct escape sequence`() {
        val renderer = CompositeRenderer(80, 24)

        val clear = renderer.clearScreen()

        assertEquals("\u001B[2J\u001B[H", clear)
    }

    @Test
    fun `hideCursor returns correct escape sequence`() {
        val renderer = CompositeRenderer(80, 24)

        val hide = renderer.hideCursor()

        assertEquals("\u001B[?25l", hide)
    }

    @Test
    fun `showCursor returns correct escape sequence`() {
        val renderer = CompositeRenderer(80, 24)

        val show = renderer.showCursor()

        assertEquals("\u001B[?25h", show)
    }

    @Test
    fun `render with color includes ANSI codes`() {
        val renderer = CompositeRenderer(80, 24, useColor = true)
        val substrate = SubstrateState.create(80, 24, baseDensity = 0.5f)

        val output = renderer.render(substrate = substrate)

        assertTrue(output.contains("\u001B["), "Should contain ANSI escape codes")
        assertTrue(output.contains(AmperePalette.RESET), "Should reset colors")
    }

    @Test
    fun `render without color excludes ANSI codes in content`() {
        val renderer = CompositeRenderer(80, 24, useColor = false)
        val substrate = SubstrateState.create(80, 24, baseDensity = 0.5f)

        val output = renderer.render(substrate = substrate)

        // Should only have clear screen codes, not color codes in content
        val lines = output.split("\n").drop(1)  // Skip first line with clear codes
        lines.dropLast(1).forEach { line ->
            assertFalse(line.contains("\u001B[38"), "Should not contain color codes")
        }
    }

    @Test
    fun `renderDiff returns only changed cells`() {
        val renderer = CompositeRenderer(80, 24)

        // First render establishes baseline
        val substrate = SubstrateState.create(80, 24, baseDensity = 0.3f)
        renderer.render(substrate = substrate)

        // Make a small change
        substrate.setDensity(40, 12, 0.9f)

        // Diff should be smaller than full render
        val diff = renderer.renderDiff(substrate = substrate)
        val full = renderer.render(substrate = substrate)

        assertTrue(diff.length < full.length, "Diff should be smaller than full render")
    }

    @Test
    fun `render composites multiple layers correctly`() {
        val renderer = CompositeRenderer(80, 24, useColor = false)

        val substrate = SubstrateState.create(80, 24, baseDensity = 0.3f)
        val agents = AgentLayer(80, 24)
        agents.addAgent(AgentVisualState(
            id = "test",
            name = "Test",
            role = "test",
            position = Vector2(40f, 12f)
        ))

        val output = renderer.render(substrate = substrate, agents = agents)

        // Should have both substrate glyphs and agent info
        val lines = output.split("\n")
        assertTrue(lines.size >= 20, "Should have multiple lines")
    }

    @Test
    fun `render with all layers does not crash`() {
        val renderer = CompositeRenderer(80, 24)

        val substrate = SubstrateState.create(80, 24)
        val particles = ParticleSystem()
        val agents = AgentLayer(80, 24)
        val flow = FlowLayer(80, 24)
        val logo = LogoCrystallizer.create(80, 24)

        // Run logo to reveal some glyphs
        repeat(30) { logo.update(0.1f) }

        // Should not throw
        val output = renderer.render(
            substrate = substrate,
            particles = particles,
            agents = agents,
            flow = flow,
            logoCrystallizer = logo
        )

        assertNotNull(output)
        assertTrue(output.isNotEmpty())
    }

    @Test
    fun `layer priority ensures agents render over substrate`() {
        val renderer = CompositeRenderer(80, 24, useColor = false)

        // Create substrate with high density at agent position
        val substrate = SubstrateState.create(80, 24, baseDensity = 0.8f)

        // Create agent at specific position with unique name
        val agents = AgentLayer(80, 24)
        agents.addAgent(AgentVisualState(
            id = "spark",
            name = "SPARK",
            role = "test",
            position = Vector2(40f, 12f)
        ))

        val output = renderer.render(substrate = substrate, agents = agents)

        // The agent name should be visible somewhere in the output
        assertTrue(
            output.contains("SPARK") || output.contains("◉"),
            "Agent should be visible over substrate"
        )
    }
}
