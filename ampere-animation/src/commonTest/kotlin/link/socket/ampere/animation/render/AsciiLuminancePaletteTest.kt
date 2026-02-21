package link.socket.ampere.animation.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

class AsciiLuminancePaletteTest {

    @Test
    fun `darkest luminance returns first character`() {
        assertEquals(' ', AsciiLuminancePalette.STANDARD.charForLuminance(0.0f))
    }

    @Test
    fun `brightest luminance returns last character`() {
        val palette = AsciiLuminancePalette.STANDARD
        assertEquals(palette.characters.last(), palette.charForLuminance(1.0f))
    }

    @Test
    fun `luminance is clamped below zero`() {
        assertEquals(' ', AsciiLuminancePalette.STANDARD.charForLuminance(-0.5f))
    }

    @Test
    fun `luminance is clamped above one`() {
        val palette = AsciiLuminancePalette.STANDARD
        assertEquals(palette.characters.last(), palette.charForLuminance(1.5f))
    }

    @Test
    fun `mid luminance returns middle character`() {
        val palette = AsciiLuminancePalette("ABCDE", "test")
        assertEquals('C', palette.charForLuminance(0.5f))
    }

    @Test
    fun `single character palette always returns that character`() {
        val palette = AsciiLuminancePalette("X", "single")
        assertEquals('X', palette.charForLuminance(0.0f))
        assertEquals('X', palette.charForLuminance(0.5f))
        assertEquals('X', palette.charForLuminance(1.0f))
    }

    @Test
    fun `empty palette throws`() {
        assertFailsWith<IllegalArgumentException> {
            AsciiLuminancePalette("", "empty")
        }
    }

    @Test
    fun `charForSurface with strong right normal returns slash`() {
        val palette = AsciiLuminancePalette.STANDARD
        assertEquals('/', palette.charForSurface(0.5f, 0.8f, 0.0f))
    }

    @Test
    fun `charForSurface with strong left normal returns backslash`() {
        val palette = AsciiLuminancePalette.STANDARD
        assertEquals('\\', palette.charForSurface(0.5f, -0.8f, 0.0f))
    }

    @Test
    fun `charForSurface with strong vertical normal returns pipe`() {
        val palette = AsciiLuminancePalette.STANDARD
        assertEquals('|', palette.charForSurface(0.5f, 0.0f, 0.8f))
    }

    @Test
    fun `charForSurface with weak normals falls back to luminance`() {
        val palette = AsciiLuminancePalette.STANDARD
        val luminanceChar = palette.charForLuminance(0.5f)
        assertEquals(luminanceChar, palette.charForSurface(0.5f, 0.1f, 0.1f))
    }

    @Test
    fun `charForSurface at very low luminance ignores normals`() {
        val palette = AsciiLuminancePalette.STANDARD
        // At luminance 0.05, below the 0.15 threshold, should use luminance char
        val luminanceChar = palette.charForLuminance(0.05f)
        assertEquals(luminanceChar, palette.charForSurface(0.05f, 0.9f, 0.0f))
    }

    @Test
    fun `charForSurface at very high luminance ignores normals`() {
        val palette = AsciiLuminancePalette.STANDARD
        val luminanceChar = palette.charForLuminance(0.95f)
        assertEquals(luminanceChar, palette.charForSurface(0.95f, 0.9f, 0.0f))
    }

    @Test
    fun `all phase palettes have at least 5 characters`() {
        val palettes = listOf(
            AsciiLuminancePalette.STANDARD,
            AsciiLuminancePalette.PERCEIVE,
            AsciiLuminancePalette.RECALL,
            AsciiLuminancePalette.PLAN,
            AsciiLuminancePalette.EXECUTE,
            AsciiLuminancePalette.EVALUATE
        )
        palettes.forEach { palette ->
            assert(palette.characters.length >= 5) {
                "${palette.name} palette has only ${palette.characters.length} characters, need at least 5"
            }
        }
    }

    @Test
    fun `increasing luminance produces non-decreasing density of characters`() {
        val palette = AsciiLuminancePalette.STANDARD
        var prevIndex = -1
        for (i in 0..10) {
            val luminance = i / 10f
            val ch = palette.charForLuminance(luminance)
            val index = palette.characters.indexOf(ch)
            assert(index >= prevIndex) {
                "Character at luminance $luminance went backwards: index $index < $prevIndex"
            }
            prevIndex = index
        }
    }

    // --- Dithered character selection ---

    @Test
    fun `dithered luminance extremes are stable`() {
        // At 0.0 and 1.0, dithering should not push beyond palette bounds
        val palette = AsciiLuminancePalette.STANDARD
        for (y in 0..3) {
            for (x in 0..3) {
                assertEquals(' ', palette.charForLuminanceDithered(0.0f, x, y))
                assertEquals(palette.characters.last(), palette.charForLuminanceDithered(1.0f, x, y))
            }
        }
    }

    @Test
    fun `dithering produces variation at mid-luminance`() {
        // At a luminance near a character boundary, different screen positions
        // should produce at least two different characters
        val palette = AsciiLuminancePalette("ABCDE", "test")
        // Boundary between B and C is at luminance 0.375 (1.5/4)
        val chars = mutableSetOf<Char>()
        for (y in 0..3) {
            for (x in 0..3) {
                chars.add(palette.charForLuminanceDithered(0.375f, x, y))
            }
        }
        assert(chars.size >= 2) {
            "Dithering at boundary luminance should produce at least 2 characters, got $chars"
        }
    }

    @Test
    fun `dithered surface falls back to dithered luminance for weak normals`() {
        val palette = AsciiLuminancePalette.STANDARD
        val ditheredLum = palette.charForLuminanceDithered(0.5f, 3, 7)
        val ditheredSurf = palette.charForSurfaceDithered(0.5f, 0.1f, 0.1f, 3, 7)
        assertEquals(ditheredLum, ditheredSurf)
    }

    @Test
    fun `dithered surface still returns edge chars for strong normals`() {
        val palette = AsciiLuminancePalette.STANDARD
        assertEquals('/', palette.charForSurfaceDithered(0.5f, 0.8f, 0.0f, 0, 0))
        assertEquals('\\', palette.charForSurfaceDithered(0.5f, -0.8f, 0.0f, 2, 3))
        assertEquals('|', palette.charForSurfaceDithered(0.5f, 0.0f, 0.8f, 1, 1))
    }

    @Test
    fun `single character palette dithering is stable`() {
        val palette = AsciiLuminancePalette("X", "single")
        for (y in 0..3) {
            for (x in 0..3) {
                assertEquals('X', palette.charForLuminanceDithered(0.5f, x, y))
            }
        }
    }

    @Test
    fun `different phase palettes produce different characters for same luminance`() {
        // At mid-luminance, phase palettes should differ from each other
        val perceiveChar = AsciiLuminancePalette.PERCEIVE.charForLuminance(0.7f)
        val executeChar = AsciiLuminancePalette.EXECUTE.charForLuminance(0.7f)
        assertNotEquals(perceiveChar, executeChar)
    }
}
