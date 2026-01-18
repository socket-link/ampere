package link.socket.ampere.repl

import com.github.ajalt.mordant.rendering.AnsiLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalColorsTest {

    @BeforeEach
    fun setup() {
        TerminalColors.reset()
    }

    @AfterEach
    fun tearDown() {
        TerminalColors.reset()
    }

    @Test
    fun `reset sets defaults`() {
        TerminalColors.enabled = false
        TerminalColors.unicodeEnabled = false

        TerminalColors.reset()

        assertTrue(TerminalColors.enabled)
        assertTrue(TerminalColors.unicodeEnabled)
    }

    @Test
    fun `success includes checkmark when colors enabled`() {
        TerminalColors.enabled = true
        TerminalColors.unicodeEnabled = true

        val result = TerminalColors.success("test")
        assertTrue(result.contains("✓"))
        assertTrue(result.contains("test"))
    }

    @Test
    fun `success uses ASCII checkmark when unicode disabled`() {
        TerminalColors.enabled = true
        TerminalColors.unicodeEnabled = false

        val result = TerminalColors.success("test")
        assertTrue(result.contains("[OK]"))
        assertTrue(result.contains("test"))
    }

    @Test
    fun `error includes cross when colors enabled`() {
        TerminalColors.enabled = true
        TerminalColors.unicodeEnabled = true

        val result = TerminalColors.error("test")
        assertTrue(result.contains("✗"))
        assertTrue(result.contains("test"))
    }

    @Test
    fun `error uses ASCII cross when unicode disabled`() {
        TerminalColors.enabled = true
        TerminalColors.unicodeEnabled = false

        val result = TerminalColors.error("test")
        assertTrue(result.contains("[X]"))
        assertTrue(result.contains("test"))
    }

    @Test
    fun `warning includes warning symbol when colors enabled`() {
        TerminalColors.enabled = true
        TerminalColors.unicodeEnabled = true

        val result = TerminalColors.warning("test")
        assertTrue(result.contains("⚠"))
        assertTrue(result.contains("test"))
    }

    @Test
    fun `warning uses ASCII warning when unicode disabled`() {
        TerminalColors.enabled = true
        TerminalColors.unicodeEnabled = false

        val result = TerminalColors.warning("test")
        assertTrue(result.contains("[!]"))
        assertTrue(result.contains("test"))
    }

    @Test
    fun `info returns message without ANSI when disabled`() {
        TerminalColors.enabled = false

        val result = TerminalColors.info("test")
        assertEquals("test", result)
    }

    @Test
    fun `info includes ANSI codes when enabled`() {
        TerminalColors.enabled = true

        val result = TerminalColors.info("test")
        assertTrue(result.contains("\u001B["))
        assertTrue(result.contains("test"))
    }

    @Test
    fun `infoWithSymbol includes info symbol`() {
        TerminalColors.enabled = true
        TerminalColors.unicodeEnabled = true

        val result = TerminalColors.infoWithSymbol("test")
        assertTrue(result.contains("ℹ"))
        assertTrue(result.contains("test"))
    }

    @Test
    fun `infoWithSymbol uses ASCII when unicode disabled`() {
        TerminalColors.enabled = true
        TerminalColors.unicodeEnabled = false

        val result = TerminalColors.infoWithSymbol("test")
        assertTrue(result.contains("[i]"))
        assertTrue(result.contains("test"))
    }

    @Test
    fun `dim returns message when disabled`() {
        TerminalColors.enabled = false

        val result = TerminalColors.dim("test")
        assertEquals("test", result)
    }

    @Test
    fun `emphasis returns message when disabled`() {
        TerminalColors.enabled = false

        val result = TerminalColors.emphasis("test")
        assertEquals("test", result)
    }

    @Test
    fun `highlight returns message when disabled`() {
        TerminalColors.enabled = false

        val result = TerminalColors.highlight("test")
        assertEquals("test", result)
    }

    @Test
    fun `initializeFromCapabilities sets enabled from capabilities`() {
        val capabilities = TerminalFactory.TerminalCapabilities(
            supportsUnicode = false,
            supportsColors = false,
            colorLevel = AnsiLevel.NONE,
            isInteractive = false,
            width = 80,
            height = 24
        )

        TerminalColors.initializeFromCapabilities(capabilities)

        assertFalse(TerminalColors.enabled)
        assertFalse(TerminalColors.unicodeEnabled)
    }

    @Test
    fun `initializeFromCapabilities enables colors when supported`() {
        val capabilities = TerminalFactory.TerminalCapabilities(
            supportsUnicode = true,
            supportsColors = true,
            colorLevel = AnsiLevel.TRUECOLOR,
            isInteractive = true,
            width = 120,
            height = 40
        )

        TerminalColors.initializeFromCapabilities(capabilities)

        assertTrue(TerminalColors.enabled)
        assertTrue(TerminalColors.unicodeEnabled)
    }

    @Test
    fun `success without colors still includes symbol`() {
        TerminalColors.enabled = false
        TerminalColors.unicodeEnabled = true

        val result = TerminalColors.success("test")
        assertTrue(result.contains("✓"))
        assertFalse(result.contains("\u001B["))
    }

    @Test
    fun `error without colors still includes symbol`() {
        TerminalColors.enabled = false
        TerminalColors.unicodeEnabled = true

        val result = TerminalColors.error("test")
        assertTrue(result.contains("✗"))
        assertFalse(result.contains("\u001B["))
    }
}
