package link.socket.ampere.repl

import com.github.ajalt.mordant.rendering.AnsiLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalSymbolsTest {

    @BeforeEach
    fun setup() {
        TerminalSymbols.reset()
    }

    @AfterEach
    fun tearDown() {
        TerminalSymbols.reset()
    }

    @Test
    fun `reset sets defaults to Unicode and interactive`() {
        TerminalSymbols.forceAscii()
        TerminalSymbols.forceNonInteractive()

        TerminalSymbols.reset()

        assertTrue(TerminalSymbols.useUnicode)
        assertTrue(TerminalSymbols.isInteractive)
    }

    @Test
    fun `forceAscii disables unicode`() {
        TerminalSymbols.forceAscii()

        assertFalse(TerminalSymbols.useUnicode)
    }

    @Test
    fun `forceNonInteractive disables interactive mode`() {
        TerminalSymbols.forceNonInteractive()

        assertFalse(TerminalSymbols.isInteractive)
    }

    // ============================================================
    // Status Symbols Tests
    // ============================================================

    @Test
    fun `status check returns Unicode when enabled`() {
        assertTrue(TerminalSymbols.useUnicode)
        assertEquals("✓", TerminalSymbols.Status.check)
    }

    @Test
    fun `status check returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("[OK]", TerminalSymbols.Status.check)
    }

    @Test
    fun `status cross returns Unicode when enabled`() {
        assertEquals("✗", TerminalSymbols.Status.cross)
    }

    @Test
    fun `status cross returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("[X]", TerminalSymbols.Status.cross)
    }

    @Test
    fun `status warning returns Unicode when enabled`() {
        assertEquals("⚠", TerminalSymbols.Status.warning)
    }

    @Test
    fun `status warning returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("[!]", TerminalSymbols.Status.warning)
    }

    @Test
    fun `status filledCircle returns Unicode when enabled`() {
        assertEquals("●", TerminalSymbols.Status.filledCircle)
    }

    @Test
    fun `status filledCircle returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("o", TerminalSymbols.Status.filledCircle)
    }

    @Test
    fun `status emptyCircle returns Unicode when enabled`() {
        assertEquals("○", TerminalSymbols.Status.emptyCircle)
    }

    @Test
    fun `status emptyCircle returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals(".", TerminalSymbols.Status.emptyCircle)
    }

    @Test
    fun `status lightning returns Unicode when enabled`() {
        assertEquals("⚡", TerminalSymbols.Status.lightning)
    }

    @Test
    fun `status lightning returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("*", TerminalSymbols.Status.lightning)
    }

    // ============================================================
    // Spinner Tests
    // ============================================================

    @Test
    fun `spinner frames returns Unicode braille when enabled`() {
        val frames = TerminalSymbols.Spinner.frames
        assertEquals(10, frames.size)
        assertTrue(frames.first().contains("⠋"))
    }

    @Test
    fun `spinner frames returns ASCII rotation when disabled`() {
        TerminalSymbols.forceAscii()
        val frames = TerminalSymbols.Spinner.frames
        assertEquals(4, frames.size)
        assertEquals(listOf("-", "\\", "|", "/"), frames)
    }

    @Test
    fun `spinner static indicator is always ASCII`() {
        assertEquals("[*]", TerminalSymbols.Spinner.staticIndicator)
    }

    @Test
    fun `halfCircle frames returns Unicode when enabled`() {
        val frames = TerminalSymbols.Spinner.halfCircleFrames
        assertEquals(4, frames.size)
        assertTrue(frames.contains("◐"))
    }

    @Test
    fun `halfCircle frames returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        val frames = TerminalSymbols.Spinner.halfCircleFrames
        assertEquals(4, frames.size)
        assertEquals(listOf("-", "\\", "|", "/"), frames)
    }

    // ============================================================
    // Box Drawing Tests
    // ============================================================

    @Test
    fun `box characters return Unicode when enabled`() {
        assertEquals("┌", TerminalSymbols.Box.topLeft)
        assertEquals("┐", TerminalSymbols.Box.topRight)
        assertEquals("└", TerminalSymbols.Box.bottomLeft)
        assertEquals("┘", TerminalSymbols.Box.bottomRight)
        assertEquals("─", TerminalSymbols.Box.horizontal)
        assertEquals("│", TerminalSymbols.Box.vertical)
        assertEquals("╌", TerminalSymbols.Box.horizontalDashed)
    }

    @Test
    fun `box characters return ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("+", TerminalSymbols.Box.topLeft)
        assertEquals("+", TerminalSymbols.Box.topRight)
        assertEquals("+", TerminalSymbols.Box.bottomLeft)
        assertEquals("+", TerminalSymbols.Box.bottomRight)
        assertEquals("-", TerminalSymbols.Box.horizontal)
        assertEquals("|", TerminalSymbols.Box.vertical)
        assertEquals("-", TerminalSymbols.Box.horizontalDashed)
    }

    // ============================================================
    // Arrow Tests
    // ============================================================

    @Test
    fun `arrow right returns Unicode when enabled`() {
        assertEquals("▶", TerminalSymbols.Arrow.right)
    }

    @Test
    fun `arrow right returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals(">", TerminalSymbols.Arrow.right)
    }

    @Test
    fun `arrow forward returns Unicode when enabled`() {
        assertEquals("──▶", TerminalSymbols.Arrow.forward)
    }

    @Test
    fun `arrow forward returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("-->", TerminalSymbols.Arrow.forward)
    }

    @Test
    fun `arrow backward returns Unicode when enabled`() {
        assertEquals("◀──", TerminalSymbols.Arrow.backward)
    }

    @Test
    fun `arrow backward returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("<--", TerminalSymbols.Arrow.backward)
    }

    // ============================================================
    // Lightning Animation Tests
    // ============================================================

    @Test
    fun `lightning discharge returns Unicode symbols when enabled`() {
        val discharge = TerminalSymbols.Lightning.discharge
        assertEquals(11, discharge.size)
        assertTrue(discharge.contains("ϟ"))
        assertTrue(discharge.contains("✦"))
    }

    @Test
    fun `lightning discharge returns ASCII symbols when disabled`() {
        TerminalSymbols.forceAscii()
        val discharge = TerminalSymbols.Lightning.discharge
        assertEquals(11, discharge.size)
        assertTrue(discharge.contains("#"))
        assertTrue(discharge.contains("*"))
        assertFalse(discharge.contains("ϟ"))
    }

    @Test
    fun `lightning compact returns Unicode symbols when enabled`() {
        val compact = TerminalSymbols.Lightning.compact
        assertEquals(6, compact.size)
        assertTrue(compact.contains("ϟ"))
    }

    @Test
    fun `lightning compact returns ASCII symbols when disabled`() {
        TerminalSymbols.forceAscii()
        val compact = TerminalSymbols.Lightning.compact
        assertEquals(6, compact.size)
        assertTrue(compact.contains("#"))
        assertFalse(compact.contains("ϟ"))
    }

    @Test
    fun `lightning staticSymbol returns Unicode when enabled`() {
        assertEquals("⚡", TerminalSymbols.Lightning.staticSymbol)
    }

    @Test
    fun `lightning staticSymbol returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("*", TerminalSymbols.Lightning.staticSymbol)
    }

    // ============================================================
    // Block Characters Tests
    // ============================================================

    @Test
    fun `block full returns Unicode when enabled`() {
        assertEquals("█", TerminalSymbols.Block.full)
    }

    @Test
    fun `block full returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("#", TerminalSymbols.Block.full)
    }

    @Test
    fun `block light returns Unicode when enabled`() {
        assertEquals("░", TerminalSymbols.Block.light)
    }

    @Test
    fun `block light returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("-", TerminalSymbols.Block.light)
    }

    // ============================================================
    // Separator Tests
    // ============================================================

    @Test
    fun `separator vertical returns Unicode when enabled`() {
        assertEquals("│", TerminalSymbols.Separator.vertical)
    }

    @Test
    fun `separator vertical returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("|", TerminalSymbols.Separator.vertical)
    }

    @Test
    fun `separator bullet returns Unicode when enabled`() {
        assertEquals("•", TerminalSymbols.Separator.bullet)
    }

    @Test
    fun `separator bullet returns ASCII when disabled`() {
        TerminalSymbols.forceAscii()
        assertEquals("*", TerminalSymbols.Separator.bullet)
    }

    // ============================================================
    // Capability Initialization Tests
    // ============================================================

    @Test
    fun `initializeFromCapabilities sets unicode from capabilities`() {
        val capabilities = TerminalFactory.TerminalCapabilities(
            supportsUnicode = false,
            supportsColors = true,
            colorLevel = AnsiLevel.TRUECOLOR,
            isInteractive = true,
            width = 80,
            height = 24
        )

        TerminalSymbols.initializeFromCapabilities(capabilities)

        assertFalse(TerminalSymbols.useUnicode)
        assertTrue(TerminalSymbols.isInteractive)
    }

    @Test
    fun `initializeFromCapabilities sets interactive from capabilities`() {
        val capabilities = TerminalFactory.TerminalCapabilities(
            supportsUnicode = true,
            supportsColors = true,
            colorLevel = AnsiLevel.TRUECOLOR,
            isInteractive = false,
            width = 80,
            height = 24
        )

        TerminalSymbols.initializeFromCapabilities(capabilities)

        assertTrue(TerminalSymbols.useUnicode)
        assertFalse(TerminalSymbols.isInteractive)
    }

    @Test
    fun `initializeFromCapabilities with safe defaults disables everything`() {
        TerminalSymbols.initializeFromCapabilities(
            TerminalFactory.TerminalCapabilities.SAFE_DEFAULTS
        )

        assertFalse(TerminalSymbols.useUnicode)
        assertFalse(TerminalSymbols.isInteractive)
    }
}
