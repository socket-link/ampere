package link.socket.ampere.animation.render

import link.socket.ampere.animation.agent.CognitivePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AsciiCellTest {

    @Test
    fun `EMPTY cell has space character and default color`() {
        assertEquals(' ', AsciiCell.EMPTY.char)
        assertEquals(7, AsciiCell.EMPTY.fgColor)
        assertNull(AsciiCell.EMPTY.bgColor)
        assertFalse(AsciiCell.EMPTY.bold)
    }

    @Test
    fun `fromSurface produces bright character for high luminance`() {
        val cell = AsciiCell.fromSurface(
            luminance = 0.9f,
            normalX = 0f,
            normalY = 0f,
            palette = AsciiLuminancePalette.STANDARD,
            colorRamp = CognitiveColorRamp.PERCEIVE
        )
        assertNotEquals(' ', cell.char)
        assertTrue(cell.bold)
    }

    @Test
    fun `fromSurface produces space for zero luminance`() {
        val cell = AsciiCell.fromSurface(
            luminance = 0f,
            normalX = 0f,
            normalY = 0f,
            palette = AsciiLuminancePalette.STANDARD,
            colorRamp = CognitiveColorRamp.EXECUTE
        )
        assertEquals(' ', cell.char)
        assertFalse(cell.bold)
    }

    @Test
    fun `fromSurface uses color ramp for fg color`() {
        val ramp = CognitiveColorRamp.EXECUTE
        val cell = AsciiCell.fromSurface(
            luminance = 0.5f,
            normalX = 0f,
            normalY = 0f,
            palette = AsciiLuminancePalette.STANDARD,
            colorRamp = ramp
        )
        assertEquals(ramp.colorForLuminance(0.5f), cell.fgColor)
    }

    @Test
    fun `fromSurface respects surface normals`() {
        val cell = AsciiCell.fromSurface(
            luminance = 0.5f,
            normalX = 0.9f,
            normalY = 0f,
            palette = AsciiLuminancePalette.STANDARD,
            colorRamp = CognitiveColorRamp.PLAN
        )
        assertEquals('/', cell.char)
    }

    @Test
    fun `fromSurface with left normal returns backslash`() {
        val cell = AsciiCell.fromSurface(
            luminance = 0.5f,
            normalX = -0.9f,
            normalY = 0f,
            palette = AsciiLuminancePalette.STANDARD,
            colorRamp = CognitiveColorRamp.PLAN
        )
        assertEquals('\\', cell.char)
    }

    @Test
    fun `fromSurface bold threshold at 0_8`() {
        val notBold = AsciiCell.fromSurface(
            luminance = 0.79f,
            normalX = 0f,
            normalY = 0f,
            palette = AsciiLuminancePalette.STANDARD,
            colorRamp = CognitiveColorRamp.PERCEIVE
        )
        val isBold = AsciiCell.fromSurface(
            luminance = 0.81f,
            normalX = 0f,
            normalY = 0f,
            palette = AsciiLuminancePalette.STANDARD,
            colorRamp = CognitiveColorRamp.PERCEIVE
        )
        assertFalse(notBold.bold)
        assertTrue(isBold.bold)
    }

    @Test
    fun `fromSurface bgColor is null`() {
        val cell = AsciiCell.fromSurface(
            luminance = 0.5f,
            normalX = 0f,
            normalY = 0f,
            palette = AsciiLuminancePalette.STANDARD,
            colorRamp = CognitiveColorRamp.EVALUATE
        )
        assertNull(cell.bgColor)
    }

    @Test
    fun `different phases produce different cells at same luminance`() {
        val perceiveCell = AsciiCell.fromSurface(
            luminance = 0.6f,
            normalX = 0f,
            normalY = 0f,
            palette = AsciiLuminancePalette.PERCEIVE,
            colorRamp = CognitiveColorRamp.PERCEIVE
        )
        val executeCell = AsciiCell.fromSurface(
            luminance = 0.6f,
            normalX = 0f,
            normalY = 0f,
            palette = AsciiLuminancePalette.EXECUTE,
            colorRamp = CognitiveColorRamp.EXECUTE
        )
        assertNotEquals(perceiveCell, executeCell)
    }
}
