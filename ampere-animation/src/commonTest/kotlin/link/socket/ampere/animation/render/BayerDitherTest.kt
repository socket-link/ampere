package link.socket.ampere.animation.render

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BayerDitherTest {

    @Test
    fun `threshold values are in valid range`() {
        for (y in 0..3) {
            for (x in 0..3) {
                val t = BayerDither.threshold(x, y)
                assertTrue(t >= 0f, "threshold($x, $y) = $t < 0")
                assertTrue(t < 1f, "threshold($x, $y) = $t >= 1")
            }
        }
    }

    @Test
    fun `matrix tiles at period 4`() {
        for (y in 0..3) {
            for (x in 0..3) {
                assertEquals(
                    BayerDither.threshold(x, y),
                    BayerDither.threshold(x + 4, y + 4),
                    "threshold not periodic at ($x, $y)"
                )
            }
        }
    }

    @Test
    fun `all 16 thresholds are distinct`() {
        val values = mutableSetOf<Float>()
        for (y in 0..3) {
            for (x in 0..3) {
                values.add(BayerDither.threshold(x, y))
            }
        }
        assertEquals(16, values.size, "Bayer 4x4 should produce 16 distinct thresholds")
    }

    @Test
    fun `thresholds are evenly spaced`() {
        val values = mutableListOf<Float>()
        for (y in 0..3) {
            for (x in 0..3) {
                values.add(BayerDither.threshold(x, y))
            }
        }
        values.sort()
        // Should be 0/16, 1/16, 2/16, ..., 15/16
        for (i in values.indices) {
            assertEquals(i / 16f, values[i], "threshold at sorted position $i")
        }
    }

    @Test
    fun `adjacent cells get different thresholds`() {
        assertNotEquals(BayerDither.threshold(0, 0), BayerDither.threshold(1, 0))
        assertNotEquals(BayerDither.threshold(0, 0), BayerDither.threshold(0, 1))
        assertNotEquals(BayerDither.threshold(1, 1), BayerDither.threshold(2, 1))
    }

    @Test
    fun `negative coordinates produce valid threshold`() {
        val t = BayerDither.threshold(-1, -1)
        assertTrue(t >= 0f && t < 1f, "Negative coords should produce valid threshold, got $t")
    }

    @Test
    fun `origin threshold is zero`() {
        // Bayer matrix entry 0 is at position (0,0)
        assertEquals(0f, BayerDither.threshold(0, 0))
    }
}
