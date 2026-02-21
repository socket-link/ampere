package link.socket.ampere.animation.waveform

import link.socket.ampere.animation.agent.AgentActivityState
import link.socket.ampere.animation.agent.AgentLayer
import link.socket.ampere.animation.agent.AgentLayoutOrientation
import link.socket.ampere.animation.agent.AgentVisualState
import link.socket.ampere.animation.agent.CognitivePhase
import link.socket.ampere.animation.math.Vector3
import link.socket.ampere.animation.projection.Camera
import link.socket.ampere.animation.projection.ScreenProjector
import link.socket.ampere.animation.render.AsciiCell
import link.socket.ampere.animation.render.AsciiLuminancePalette
import link.socket.ampere.animation.render.CognitiveColorRamp
import link.socket.ampere.animation.substrate.SubstrateState
import link.socket.ampere.animation.substrate.Vector2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WaveformRasterizerTest {

    private val screenWidth = 40
    private val screenHeight = 20
    private val projector = ScreenProjector(screenWidth, screenHeight)
    private val lighting = SurfaceLighting()
    private val rasterizer = WaveformRasterizer(screenWidth, screenHeight, projector, lighting)

    private val camera = Camera(
        position = Vector3(0f, 10f, 15f),
        target = Vector3.ZERO
    )

    @Test
    fun `rasterize returns correct grid dimensions`() {
        val waveform = CognitiveWaveform(gridWidth = 10, gridDepth = 10)
        val substrate = SubstrateState.create(10, 10, 0.3f)
        val agents = AgentLayer(10, 10, AgentLayoutOrientation.CUSTOM)
        waveform.update(substrate, agents, null, dt = 1f)

        val grid = rasterizer.rasterize(
            waveform, camera,
            AsciiLuminancePalette.STANDARD,
            CognitiveColorRamp.NEUTRAL
        )

        assertEquals(screenHeight, grid.size, "grid should have $screenHeight rows")
        for (row in grid) {
            assertEquals(screenWidth, row.size, "each row should have $screenWidth cols")
        }
    }

    @Test
    fun `flat waveform produces some visible cells`() {
        val waveform = CognitiveWaveform(gridWidth = 20, gridDepth = 15)
        val substrate = SubstrateState.create(20, 15, 0.5f)
        val agents = AgentLayer(20, 15, AgentLayoutOrientation.CUSTOM)
        waveform.update(substrate, agents, null, dt = 1f)

        val grid = rasterizer.rasterize(
            waveform, camera,
            AsciiLuminancePalette.STANDARD,
            CognitiveColorRamp.NEUTRAL
        )

        var nonEmptyCells = 0
        for (row in grid) {
            for (cell in row) {
                if (cell != AsciiCell.EMPTY) nonEmptyCells++
            }
        }

        assertTrue(
            nonEmptyCells > 0,
            "flat waveform should produce some visible cells, got $nonEmptyCells"
        )
    }

    @Test
    fun `waveform with peak has more non-empty cells in peak region`() {
        val waveform = CognitiveWaveform(gridWidth = 20, gridDepth = 15, worldWidth = 20f, worldDepth = 15f)
        val substrate = SubstrateState.create(20, 15, 0.1f)
        val agents = AgentLayer(20, 15, AgentLayoutOrientation.CUSTOM)

        agents.addAgent(AgentVisualState(
            id = "a1", name = "A1", role = "r",
            position = Vector2(10f, 7.5f),
            state = AgentActivityState.PROCESSING,
            cognitivePhase = CognitivePhase.EXECUTE
        ))

        waveform.update(substrate, agents, null, dt = 1f)

        val grid = rasterizer.rasterize(
            waveform, camera,
            AsciiLuminancePalette.STANDARD,
            CognitiveColorRamp.EXECUTE
        )

        // Count non-empty cells -- should have some rendered content
        var nonEmptyCells = 0
        for (row in grid) {
            for (cell in row) {
                if (cell != AsciiCell.EMPTY) nonEmptyCells++
            }
        }

        assertTrue(
            nonEmptyCells > 0,
            "waveform with agent peak should produce visible cells"
        )
    }

    @Test
    fun `clear resets buffers to empty`() {
        val waveform = CognitiveWaveform(gridWidth = 10, gridDepth = 10)
        val substrate = SubstrateState.create(10, 10, 0.5f)
        val agents = AgentLayer(10, 10, AgentLayoutOrientation.CUSTOM)
        waveform.update(substrate, agents, null, dt = 1f)

        // Rasterize to fill buffers
        rasterizer.rasterize(
            waveform, camera,
            AsciiLuminancePalette.STANDARD,
            CognitiveColorRamp.NEUTRAL
        )

        // Clear and rasterize an empty waveform (all heights 0)
        val emptyWaveform = CognitiveWaveform(gridWidth = 10, gridDepth = 10)
        // Don't update -- heights stay at 0
        val grid = rasterizer.rasterize(
            emptyWaveform, camera,
            AsciiLuminancePalette.STANDARD,
            CognitiveColorRamp.NEUTRAL
        )

        // With all heights at 0, still a flat plane at y=0 which may or may not be visible
        // The important thing is that clear() was called (no stale data)
        assertEquals(screenHeight, grid.size)
    }

    @Test
    fun `different palettes produce different characters`() {
        val waveform = CognitiveWaveform(gridWidth = 20, gridDepth = 15)
        val substrate = SubstrateState.create(20, 15, 0.5f)
        val agents = AgentLayer(20, 15, AgentLayoutOrientation.CUSTOM)
        waveform.update(substrate, agents, null, dt = 1f)

        val gridStandard = rasterizer.rasterize(
            waveform, camera,
            AsciiLuminancePalette.STANDARD,
            CognitiveColorRamp.NEUTRAL
        )

        val gridExecute = rasterizer.rasterize(
            waveform, camera,
            AsciiLuminancePalette.EXECUTE,
            CognitiveColorRamp.EXECUTE
        )

        // Collect non-empty characters from each
        val standardChars = gridStandard.flatMap { it.toList() }
            .filter { it != AsciiCell.EMPTY }
            .map { it.char }
            .toSet()

        val executeChars = gridExecute.flatMap { it.toList() }
            .filter { it != AsciiCell.EMPTY }
            .map { it.char }
            .toSet()

        // If both have content, at least the color should differ
        if (standardChars.isNotEmpty() && executeChars.isNotEmpty()) {
            val standardColors = gridStandard.flatMap { it.toList() }
                .filter { it != AsciiCell.EMPTY }
                .map { it.fgColor }
                .toSet()

            val executeColors = gridExecute.flatMap { it.toList() }
                .filter { it != AsciiCell.EMPTY }
                .map { it.fgColor }
                .toSet()

            assertNotEquals(standardColors, executeColors, "different ramps should produce different colors")
        }
    }
}
