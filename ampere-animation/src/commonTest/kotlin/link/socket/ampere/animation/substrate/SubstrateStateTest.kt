package link.socket.ampere.animation.substrate

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SubstrateStateTest {

    @Test
    fun `create substrate with correct dimensions`() {
        val state = SubstrateState.create(10, 5)
        assertEquals(10, state.width)
        assertEquals(5, state.height)
        assertEquals(50, state.densityField.size)
        assertEquals(50, state.flowField.size)
    }

    @Test
    fun `create substrate with custom base density`() {
        val state = SubstrateState.create(5, 5, baseDensity = 0.7f)
        for (y in 0 until 5) {
            for (x in 0 until 5) {
                assertEquals(0.7f, state.getDensity(x, y))
            }
        }
    }

    @Test
    fun `get and set density`() {
        val state = SubstrateState.create(5, 5)
        state.setDensity(2, 3, 0.8f)
        assertEquals(0.8f, state.getDensity(2, 3))
    }

    @Test
    fun `density is clamped to valid range`() {
        val state = SubstrateState.create(5, 5)
        state.setDensity(0, 0, 1.5f)
        assertEquals(1.0f, state.getDensity(0, 0))

        state.setDensity(1, 1, -0.5f)
        assertEquals(0.0f, state.getDensity(1, 1))
    }

    @Test
    fun `out of bounds access returns zero`() {
        val state = SubstrateState.create(5, 5)
        assertEquals(0f, state.getDensity(-1, 0))
        assertEquals(0f, state.getDensity(0, -1))
        assertEquals(0f, state.getDensity(10, 0))
        assertEquals(0f, state.getDensity(0, 10))
    }

    @Test
    fun `get and set flow`() {
        val state = SubstrateState.create(5, 5)
        val flow = Vector2(0.5f, -0.3f)
        state.setFlow(2, 3, flow)
        assertEquals(flow, state.getFlow(2, 3))
    }

    @Test
    fun `withTime creates new state with updated time`() {
        val state = SubstrateState.create(5, 5)
        val newState = state.withTime(1.5f)
        assertEquals(0f, state.time)
        assertEquals(1.5f, newState.time)
    }

    @Test
    fun `withHotspots creates new state with updated hotspots`() {
        val state = SubstrateState.create(5, 5)
        val hotspots = listOf(Point(1, 1), Point(3, 3))
        val newState = state.withHotspots(hotspots)
        assertEquals(emptyList(), state.activityHotspots)
        assertEquals(hotspots, newState.activityHotspots)
    }

    @Test
    fun `invalid density field size throws`() {
        assertFailsWith<IllegalArgumentException> {
            SubstrateState(
                width = 5,
                height = 5,
                densityField = FloatArray(10), // Wrong size
                flowField = Array(25) { Vector2.ZERO }
            )
        }
    }

    @Test
    fun `invalid flow field size throws`() {
        assertFailsWith<IllegalArgumentException> {
            SubstrateState(
                width = 5,
                height = 5,
                densityField = FloatArray(25),
                flowField = Array(10) { Vector2.ZERO } // Wrong size
            )
        }
    }

    @Test
    fun `equals checks all fields`() {
        val state1 = SubstrateState.create(5, 5, 0.3f)
        val state2 = SubstrateState.create(5, 5, 0.3f)
        val state3 = SubstrateState.create(5, 5, 0.5f)

        // Same dimensions and base density should be equal
        assertEquals(state1, state2)

        // Different base density should not be equal
        assertNotEquals(state1, state3)
    }
}

class SubstrateGlyphsTest {

    @Test
    fun `forDensity returns correct glyphs for unicode mode`() {
        assertEquals('\u00B7', SubstrateGlyphs.forDensity(0.0f, useUnicode = true))
        assertEquals('\u00B7', SubstrateGlyphs.forDensity(0.1f, useUnicode = true))
        assertEquals('\u2218', SubstrateGlyphs.forDensity(0.3f, useUnicode = true))
        assertEquals('\u25E6', SubstrateGlyphs.forDensity(0.5f, useUnicode = true))
        assertEquals('\u223F', SubstrateGlyphs.forDensity(0.7f, useUnicode = true))
        assertEquals('\u224B', SubstrateGlyphs.forDensity(0.9f, useUnicode = true))
        assertEquals('\u224B', SubstrateGlyphs.forDensity(1.0f, useUnicode = true))
    }

    @Test
    fun `forDensity returns correct glyphs for ascii mode`() {
        assertEquals('.', SubstrateGlyphs.forDensity(0.1f, useUnicode = false))
        assertEquals('o', SubstrateGlyphs.forDensity(0.3f, useUnicode = false))
        assertEquals('O', SubstrateGlyphs.forDensity(0.5f, useUnicode = false))
        assertEquals('*', SubstrateGlyphs.forDensity(0.7f, useUnicode = false))
        assertEquals('#', SubstrateGlyphs.forDensity(0.9f, useUnicode = false))
    }

    @Test
    fun `getThresholds returns copy of thresholds`() {
        val thresholds = SubstrateGlyphs.getThresholds()
        assertEquals(4, thresholds.size)
        assertEquals(0.2f, thresholds[0])
        assertEquals(0.4f, thresholds[1])
        assertEquals(0.6f, thresholds[2])
        assertEquals(0.8f, thresholds[3])
    }
}
