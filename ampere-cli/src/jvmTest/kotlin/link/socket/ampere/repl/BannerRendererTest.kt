package link.socket.ampere.repl

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BannerRendererTest {

    @BeforeEach
    fun setup() {
        TerminalFactory.reset()
    }

    @AfterEach
    fun tearDown() {
        TerminalFactory.reset()
    }

    // === Banner Selection Tests ===

    @Test
    fun `selectBanner returns full banner for width 80 or greater`() {
        val banner80 = BannerRenderer.selectBanner(80)
        val banner100 = BannerRenderer.selectBanner(100)
        val banner120 = BannerRenderer.selectBanner(120)

        assertEquals(BannerRenderer.getFullBanner(), banner80)
        assertEquals(BannerRenderer.getFullBanner(), banner100)
        assertEquals(BannerRenderer.getFullBanner(), banner120)
    }

    @Test
    fun `selectBanner returns compact banner for width 60 to 79`() {
        val banner60 = BannerRenderer.selectBanner(60)
        val banner70 = BannerRenderer.selectBanner(70)
        val banner79 = BannerRenderer.selectBanner(79)

        assertEquals(BannerRenderer.getCompactBanner(), banner60)
        assertEquals(BannerRenderer.getCompactBanner(), banner70)
        assertEquals(BannerRenderer.getCompactBanner(), banner79)
    }

    @Test
    fun `selectBanner returns minimal banner for width less than 60`() {
        val banner59 = BannerRenderer.selectBanner(59)
        val banner40 = BannerRenderer.selectBanner(40)
        val banner20 = BannerRenderer.selectBanner(20)

        assertEquals(BannerRenderer.getMinimalBanner(), banner59)
        assertEquals(BannerRenderer.getMinimalBanner(), banner40)
        assertEquals(BannerRenderer.getMinimalBanner(), banner20)
    }

    @Test
    fun `selectBanner handles edge case at full breakpoint boundary`() {
        val banner79 = BannerRenderer.selectBanner(79)
        val banner80 = BannerRenderer.selectBanner(80)

        assertEquals(BannerRenderer.getCompactBanner(), banner79)
        assertEquals(BannerRenderer.getFullBanner(), banner80)
    }

    @Test
    fun `selectBanner handles edge case at compact breakpoint boundary`() {
        val banner59 = BannerRenderer.selectBanner(59)
        val banner60 = BannerRenderer.selectBanner(60)

        assertEquals(BannerRenderer.getMinimalBanner(), banner59)
        assertEquals(BannerRenderer.getCompactBanner(), banner60)
    }

    @Test
    fun `selectBanner handles very small widths`() {
        val banner0 = BannerRenderer.selectBanner(0)
        val banner1 = BannerRenderer.selectBanner(1)
        val banner10 = BannerRenderer.selectBanner(10)

        assertEquals(BannerRenderer.getMinimalBanner(), banner0)
        assertEquals(BannerRenderer.getMinimalBanner(), banner1)
        assertEquals(BannerRenderer.getMinimalBanner(), banner10)
    }

    @Test
    fun `selectBanner handles very large widths`() {
        val banner200 = BannerRenderer.selectBanner(200)
        val banner1000 = BannerRenderer.selectBanner(1000)

        assertEquals(BannerRenderer.getFullBanner(), banner200)
        assertEquals(BannerRenderer.getFullBanner(), banner1000)
    }

    // === Banner Variant Tests ===

    @Test
    fun `getBannerVariant returns FULL for width 80 or greater`() {
        assertEquals(BannerRenderer.BannerVariant.FULL, BannerRenderer.getBannerVariant(80))
        assertEquals(BannerRenderer.BannerVariant.FULL, BannerRenderer.getBannerVariant(100))
    }

    @Test
    fun `getBannerVariant returns COMPACT for width 60 to 79`() {
        assertEquals(BannerRenderer.BannerVariant.COMPACT, BannerRenderer.getBannerVariant(60))
        assertEquals(BannerRenderer.BannerVariant.COMPACT, BannerRenderer.getBannerVariant(79))
    }

    @Test
    fun `getBannerVariant returns MINIMAL for width less than 60`() {
        assertEquals(BannerRenderer.BannerVariant.MINIMAL, BannerRenderer.getBannerVariant(59))
        assertEquals(BannerRenderer.BannerVariant.MINIMAL, BannerRenderer.getBannerVariant(20))
    }

    // === Banner Content Tests ===

    @Test
    fun `getFullBanner returns non-empty string`() {
        val banner = BannerRenderer.getFullBanner()
        assertNotNull(banner)
        assertTrue(banner.isNotEmpty())
    }

    @Test
    fun `getCompactBanner returns non-empty string`() {
        val banner = BannerRenderer.getCompactBanner()
        assertNotNull(banner)
        assertTrue(banner.isNotEmpty())
    }

    @Test
    fun `getMinimalBanner returns non-empty string`() {
        val banner = BannerRenderer.getMinimalBanner()
        assertNotNull(banner)
        assertTrue(banner.isNotEmpty())
    }

    @Test
    fun `full banner contains AMPERE text`() {
        val banner = BannerRenderer.getFullBanner()
        // Full banner uses block characters for AMPERE
        assertTrue(banner.contains("░█"))
    }

    @Test
    fun `compact banner contains AMPERE text`() {
        val banner = BannerRenderer.getCompactBanner()
        assertTrue(banner.contains("A M P E R E"))
    }

    @Test
    fun `minimal banner contains AMPERE text`() {
        val banner = BannerRenderer.getMinimalBanner()
        assertTrue(banner.contains("AMPERE"))
    }

    @Test
    fun `all banners contain lightning bolt symbol`() {
        val fullBanner = BannerRenderer.getFullBanner()
        val compactBanner = BannerRenderer.getCompactBanner()
        val minimalBanner = BannerRenderer.getMinimalBanner()

        assertTrue(fullBanner.contains("⚡"))
        assertTrue(compactBanner.contains("⚡"))
        assertTrue(minimalBanner.contains("⚡"))
    }

    @Test
    fun `full and compact banners contain network node symbol`() {
        val fullBanner = BannerRenderer.getFullBanner()
        val compactBanner = BannerRenderer.getCompactBanner()

        assertTrue(fullBanner.contains("○"))
        assertTrue(compactBanner.contains("○"))
    }

    // === Breakpoint Constant Tests ===

    @Test
    fun `breakpoint constants have expected values`() {
        assertEquals(80, BannerRenderer.Breakpoints.FULL)
        assertEquals(60, BannerRenderer.Breakpoints.COMPACT)
        assertEquals(40, BannerRenderer.Breakpoints.MINIMAL)
    }

    @Test
    fun `banner width constants have expected values`() {
        assertEquals(49, BannerRenderer.BannerWidths.FULL)
        assertEquals(40, BannerRenderer.BannerWidths.COMPACT)
        assertEquals(24, BannerRenderer.BannerWidths.MINIMAL)
    }

    // === Integration Tests ===

    @Test
    fun `selectBanner without argument uses terminal capabilities`() {
        // This tests the integration with TerminalFactory
        val banner = BannerRenderer.selectBanner()
        assertNotNull(banner)
        assertTrue(banner.isNotEmpty())
    }

    @Test
    fun `selectBanner returns consistent results for same width`() {
        val width = 75
        val banner1 = BannerRenderer.selectBanner(width)
        val banner2 = BannerRenderer.selectBanner(width)

        assertEquals(banner1, banner2)
    }
}
