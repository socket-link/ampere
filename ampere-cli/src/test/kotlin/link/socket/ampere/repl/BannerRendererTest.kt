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
    fun `selectBanner returns standard banner for width 60 to 79`() {
        val banner60 = BannerRenderer.selectBanner(60)
        val banner70 = BannerRenderer.selectBanner(70)
        val banner79 = BannerRenderer.selectBanner(79)

        assertEquals(BannerRenderer.getStandardBanner(), banner60)
        assertEquals(BannerRenderer.getStandardBanner(), banner70)
        assertEquals(BannerRenderer.getStandardBanner(), banner79)
    }

    @Test
    fun `selectBanner returns compact banner for width 40 to 59`() {
        val banner40 = BannerRenderer.selectBanner(40)
        val banner50 = BannerRenderer.selectBanner(50)
        val banner59 = BannerRenderer.selectBanner(59)

        assertEquals(BannerRenderer.getCompactBanner(), banner40)
        assertEquals(BannerRenderer.getCompactBanner(), banner50)
        assertEquals(BannerRenderer.getCompactBanner(), banner59)
    }

    @Test
    fun `selectBanner returns minimal banner for width less than 40`() {
        val banner39 = BannerRenderer.selectBanner(39)
        val banner20 = BannerRenderer.selectBanner(20)
        val banner0 = BannerRenderer.selectBanner(0)

        assertEquals(BannerRenderer.getMinimalBanner(), banner39)
        assertEquals(BannerRenderer.getMinimalBanner(), banner20)
        assertEquals(BannerRenderer.getMinimalBanner(), banner0)
    }

    @Test
    fun `selectBanner handles breakpoint boundaries`() {
        assertEquals(BannerRenderer.getMinimalBanner(), BannerRenderer.selectBanner(39))
        assertEquals(BannerRenderer.getCompactBanner(), BannerRenderer.selectBanner(40))
        assertEquals(BannerRenderer.getCompactBanner(), BannerRenderer.selectBanner(59))
        assertEquals(BannerRenderer.getStandardBanner(), BannerRenderer.selectBanner(60))
        assertEquals(BannerRenderer.getStandardBanner(), BannerRenderer.selectBanner(79))
        assertEquals(BannerRenderer.getFullBanner(), BannerRenderer.selectBanner(80))
    }

    @Test
    fun `selectBanner returns consistent results for same width`() {
        val width = 75
        val banner1 = BannerRenderer.selectBanner(width)
        val banner2 = BannerRenderer.selectBanner(width)

        assertEquals(banner1, banner2)
    }

    @Test
    fun `selectBanner returns ASCII banner when unicode is unsupported`() {
        val banner = BannerRenderer.selectBanner(80, supportsUnicode = false)
        assertAsciiOnly(banner)
        assertTrue(banner.contains("AMPERE"))
    }

    // === Banner Variant Tests ===

    @Test
    fun `getBannerVariant returns FULL for width 80 or greater`() {
        assertEquals(BannerRenderer.BannerVariant.FULL, BannerRenderer.getBannerVariant(80))
        assertEquals(BannerRenderer.BannerVariant.FULL, BannerRenderer.getBannerVariant(100))
    }

    @Test
    fun `getBannerVariant returns STANDARD for width 60 to 79`() {
        assertEquals(BannerRenderer.BannerVariant.STANDARD, BannerRenderer.getBannerVariant(60))
        assertEquals(BannerRenderer.BannerVariant.STANDARD, BannerRenderer.getBannerVariant(79))
    }

    @Test
    fun `getBannerVariant returns COMPACT for width 40 to 59`() {
        assertEquals(BannerRenderer.BannerVariant.COMPACT, BannerRenderer.getBannerVariant(40))
        assertEquals(BannerRenderer.BannerVariant.COMPACT, BannerRenderer.getBannerVariant(59))
    }

    @Test
    fun `getBannerVariant returns MINIMAL for width less than 40`() {
        assertEquals(BannerRenderer.BannerVariant.MINIMAL, BannerRenderer.getBannerVariant(39))
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
    fun `getStandardBanner returns non-empty string`() {
        val banner = BannerRenderer.getStandardBanner()
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
    fun `standard banner contains AMPERE text`() {
        val banner = BannerRenderer.getStandardBanner()
        assertTrue(banner.contains("A M P E R E"))
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
        val standardBanner = BannerRenderer.getStandardBanner()
        val compactBanner = BannerRenderer.getCompactBanner()
        val minimalBanner = BannerRenderer.getMinimalBanner()

        assertTrue(fullBanner.contains("⚡"))
        assertTrue(standardBanner.contains("⚡"))
        assertTrue(compactBanner.contains("⚡"))
        assertTrue(minimalBanner.contains("⚡"))
    }

    @Test
    fun `full standard and compact banners contain network node symbol`() {
        val fullBanner = BannerRenderer.getFullBanner()
        val standardBanner = BannerRenderer.getStandardBanner()
        val compactBanner = BannerRenderer.getCompactBanner()

        assertTrue(fullBanner.contains("○"))
        assertTrue(standardBanner.contains("○"))
        assertTrue(compactBanner.contains("○"))
    }

    // === Breakpoint Constant Tests ===

    @Test
    fun `breakpoint constants have expected values`() {
        assertEquals(80, BannerRenderer.Breakpoints.FULL)
        assertEquals(60, BannerRenderer.Breakpoints.STANDARD)
        assertEquals(40, BannerRenderer.Breakpoints.COMPACT)
        assertEquals(40, BannerRenderer.Breakpoints.MINIMAL)
    }

    @Test
    fun `banner width constants have expected values`() {
        assertEquals(49, BannerRenderer.BannerWidths.FULL)
        assertEquals(43, BannerRenderer.BannerWidths.STANDARD)
        assertEquals(40, BannerRenderer.BannerWidths.COMPACT)
        assertEquals(24, BannerRenderer.BannerWidths.MINIMAL)
    }

    // === Integration Tests ===

    @Test
    fun `selectBanner without argument uses terminal capabilities`() {
        val banner = BannerRenderer.selectBanner()
        assertNotNull(banner)
        assertTrue(banner.isNotEmpty())
    }

    private fun assertAsciiOnly(text: String) {
        assertTrue(text.all { it.code < 128 })
    }
}
