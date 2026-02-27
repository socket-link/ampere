package link.socket.ampere.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubstrateCanvasRendererTest {

    // --- shouldRenderCell ---

    @Test
    fun shouldRenderCell_zero_density_returns_false() {
        assertFalse(SubstrateCanvasRenderer.shouldRenderCell(0f))
    }

    @Test
    fun shouldRenderCell_below_threshold_returns_false() {
        assertFalse(SubstrateCanvasRenderer.shouldRenderCell(0.03f))
    }

    @Test
    fun shouldRenderCell_at_threshold_returns_false() {
        assertFalse(SubstrateCanvasRenderer.shouldRenderCell(0.05f))
    }

    @Test
    fun shouldRenderCell_above_threshold_returns_true() {
        assertTrue(SubstrateCanvasRenderer.shouldRenderCell(0.06f))
    }

    @Test
    fun shouldRenderCell_full_density_returns_true() {
        assertTrue(SubstrateCanvasRenderer.shouldRenderCell(1f))
    }

    // --- cellAlpha ---

    @Test
    fun cellAlpha_zero_density_returns_zero() {
        assertEquals(0f, SubstrateCanvasRenderer.cellAlpha(0f))
    }

    @Test
    fun cellAlpha_full_density_returns_06() {
        assertEquals(0.6f, SubstrateCanvasRenderer.cellAlpha(1f))
    }

    @Test
    fun cellAlpha_half_density_returns_03() {
        assertEquals(0.3f, SubstrateCanvasRenderer.cellAlpha(0.5f))
    }
}
