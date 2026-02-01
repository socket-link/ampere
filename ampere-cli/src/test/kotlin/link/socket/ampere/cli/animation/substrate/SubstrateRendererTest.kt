package link.socket.ampere.cli.animation.substrate

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubstrateRendererTest {

    @Test
    fun `BasicSubstrateRenderer renders correct dimensions`() {
        val state = SubstrateState.create(10, 5, baseDensity = 0.3f)
        val renderer = BasicSubstrateRenderer()

        val lines = renderer.render(state)

        assertEquals(5, lines.size, "Should have 5 rows")
        lines.forEach { line ->
            assertEquals(10, line.length, "Each row should have 10 characters")
        }
    }

    @Test
    fun `BasicSubstrateRenderer uses correct glyphs for density`() {
        val state = SubstrateState.create(5, 1, baseDensity = 0f)
        // Set different densities across the row
        state.setDensity(0, 0, 0.1f)  // minimal
        state.setDensity(1, 0, 0.3f)  // low
        state.setDensity(2, 0, 0.5f)  // medium
        state.setDensity(3, 0, 0.7f)  // high
        state.setDensity(4, 0, 0.9f)  // peak

        val renderer = BasicSubstrateRenderer(useUnicode = true)
        val lines = renderer.render(state)

        assertEquals(1, lines.size)
        assertEquals('\u00B7', lines[0][0]) // · minimal
        assertEquals('\u2218', lines[0][1]) // ∘ low
        assertEquals('\u25E6', lines[0][2]) // ◦ medium
        assertEquals('\u223F', lines[0][3]) // ∿ high
        assertEquals('\u224B', lines[0][4]) // ≋ peak
    }

    @Test
    fun `BasicSubstrateRenderer ASCII mode uses simple characters`() {
        val state = SubstrateState.create(5, 1, baseDensity = 0f)
        state.setDensity(0, 0, 0.1f)
        state.setDensity(1, 0, 0.3f)
        state.setDensity(2, 0, 0.5f)
        state.setDensity(3, 0, 0.7f)
        state.setDensity(4, 0, 0.9f)

        val renderer = BasicSubstrateRenderer(useUnicode = false)
        val lines = renderer.render(state)

        assertEquals('.', lines[0][0])
        assertEquals('o', lines[0][1])
        assertEquals('O', lines[0][2])
        assertEquals('*', lines[0][3])
        assertEquals('#', lines[0][4])
    }

    @Test
    fun `renderToString joins lines with newlines`() {
        val state = SubstrateState.create(3, 3, baseDensity = 0.5f)
        val renderer = BasicSubstrateRenderer(useUnicode = false)

        val output = renderer.renderToString(state)

        assertEquals(3, output.lines().size)
        assertTrue(output.contains("\n"))
    }

    @Test
    fun `BasicSubstrateRenderer with flow indicators shows arrows`() {
        val state = SubstrateState.create(5, 1, baseDensity = 0.3f)
        state.setFlow(0, 0, Vector2(0.5f, 0f))  // right
        state.setFlow(1, 0, Vector2(-0.5f, 0f)) // left
        state.setFlow(2, 0, Vector2(0f, 0.5f))  // down
        state.setFlow(3, 0, Vector2(0f, -0.5f)) // up
        state.setFlow(4, 0, Vector2(0.1f, 0f))  // weak flow (should show density glyph)

        val renderer = BasicSubstrateRenderer(useUnicode = true, showFlowIndicators = true)
        val lines = renderer.render(state)

        assertEquals('\u2192', lines[0][0]) // → right
        assertEquals('\u2190', lines[0][1]) // ← left
        assertEquals('\u2193', lines[0][2]) // ↓ down
        assertEquals('\u2191', lines[0][3]) // ↑ up
        // Low flow should fall back to density glyph
        assertEquals('\u2218', lines[0][4]) // ∘ low density glyph
    }

    @Test
    fun `ColoredSubstrateRenderer includes ANSI codes`() {
        val state = SubstrateState.create(3, 1, baseDensity = 0.5f)
        val renderer = ColoredSubstrateRenderer()

        val lines = renderer.render(state)

        // Output should contain ANSI escape codes
        assertTrue(lines[0].contains("\u001B["),
            "Colored output should contain ANSI escape codes")
        assertTrue(lines[0].contains("\u001B[0m"),
            "Colored output should contain reset code")
    }

    @Test
    fun `SubstrateColorScheme returns correct colors for density ranges`() {
        val scheme = SubstrateColorScheme.DEFAULT

        val lowColor = scheme.getColorForDensity(0.1f)
        val midColor = scheme.getColorForDensity(0.5f)
        val highColor = scheme.getColorForDensity(0.9f)

        // All should be valid ANSI codes
        assertTrue(lowColor.startsWith("\u001B["))
        assertTrue(midColor.startsWith("\u001B["))
        assertTrue(highColor.startsWith("\u001B["))

        // Different densities should have different colors
        assertTrue(lowColor != highColor,
            "Low and high density should have different colors")
    }

    @Test
    fun `predefined color schemes have valid colors`() {
        val schemes = listOf(
            SubstrateColorScheme.DEFAULT,
            SubstrateColorScheme.MATRIX,
            SubstrateColorScheme.ENERGY,
            SubstrateColorScheme.WARM
        )

        schemes.forEach { scheme ->
            for (density in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)) {
                val color = scheme.getColorForDensity(density)
                assertTrue(color.startsWith("\u001B["),
                    "Color for density $density should be valid ANSI code")
            }
        }
    }
}

class Vector2Test {

    @Test
    fun `vector addition works correctly`() {
        val a = Vector2(1f, 2f)
        val b = Vector2(3f, 4f)

        val result = a + b

        assertEquals(4f, result.x)
        assertEquals(6f, result.y)
    }

    @Test
    fun `vector subtraction works correctly`() {
        val a = Vector2(5f, 7f)
        val b = Vector2(2f, 3f)

        val result = a - b

        assertEquals(3f, result.x)
        assertEquals(4f, result.y)
    }

    @Test
    fun `vector scalar multiplication works correctly`() {
        val v = Vector2(2f, 3f)

        val result = v * 2f

        assertEquals(4f, result.x)
        assertEquals(6f, result.y)
    }

    @Test
    fun `vector scalar division works correctly`() {
        val v = Vector2(6f, 8f)

        val result = v / 2f

        assertEquals(3f, result.x)
        assertEquals(4f, result.y)
    }

    @Test
    fun `vector length is calculated correctly`() {
        val v = Vector2(3f, 4f)

        val length = v.length()

        assertEquals(5f, length)
    }

    @Test
    fun `zero vector has zero length`() {
        assertEquals(0f, Vector2.ZERO.length())
    }

    @Test
    fun `normalized vector has unit length`() {
        val v = Vector2(3f, 4f)
        val normalized = v.normalized()

        assertEquals(1f, normalized.length(), 0.0001f)
    }

    @Test
    fun `normalizing zero vector returns zero`() {
        val normalized = Vector2.ZERO.normalized()

        assertEquals(Vector2.ZERO, normalized)
    }

    @Test
    fun `dot product is calculated correctly`() {
        val a = Vector2(1f, 2f)
        val b = Vector2(3f, 4f)

        val dot = a.dot(b)

        assertEquals(11f, dot) // 1*3 + 2*4 = 11
    }

    @Test
    fun `lerp interpolates correctly`() {
        val a = Vector2(0f, 0f)
        val b = Vector2(10f, 20f)

        val mid = a.lerp(b, 0.5f)

        assertEquals(5f, mid.x)
        assertEquals(10f, mid.y)
    }

    @Test
    fun `lerp at 0 returns start`() {
        val a = Vector2(1f, 2f)
        val b = Vector2(10f, 20f)

        val result = a.lerp(b, 0f)

        assertEquals(a, result)
    }

    @Test
    fun `lerp at 1 returns end`() {
        val a = Vector2(1f, 2f)
        val b = Vector2(10f, 20f)

        val result = a.lerp(b, 1f)

        assertEquals(b, result)
    }

    @Test
    fun `Point toVector2 converts correctly`() {
        val point = Point(5, 10)
        val vector = point.toVector2()

        assertEquals(5f, vector.x)
        assertEquals(10f, vector.y)
    }
}
