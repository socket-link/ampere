package link.socket.ampere.renderer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SparkColorsTest {

    @Test
    fun `renderDepthIndicator supports numeric style`() {
        assertEquals("depth: 3", SparkColors.renderDepthIndicator(3, SparkColors.DepthDisplayStyle.NUMERIC))
    }

    @Test
    fun `renderDepthIndicator supports bars style with overflow`() {
        assertEquals("███░░", SparkColors.renderDepthIndicator(3, SparkColors.DepthDisplayStyle.BARS))
        assertEquals("█████+", SparkColors.renderDepthIndicator(7, SparkColors.DepthDisplayStyle.BARS))
    }

    @Test
    fun `renderDepthIndicator supports dots and arrows styles`() {
        assertEquals("●●○○○", SparkColors.renderDepthIndicator(2, SparkColors.DepthDisplayStyle.DOTS))
        assertEquals("▸▸▸", SparkColors.renderDepthIndicator(3, SparkColors.DepthDisplayStyle.ARROWS))
    }
}
