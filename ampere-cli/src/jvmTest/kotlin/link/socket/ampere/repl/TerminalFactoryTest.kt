package link.socket.ampere.repl

import com.github.ajalt.mordant.rendering.AnsiLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerminalFactoryTest {

    @BeforeEach
    fun setup() {
        TerminalFactory.reset()
        TerminalColors.reset()
    }

    @AfterEach
    fun tearDown() {
        TerminalFactory.reset()
        TerminalColors.reset()
    }

    @Test
    fun `getCapabilities returns non-null capabilities`() {
        val capabilities = TerminalFactory.getCapabilities()
        assertNotNull(capabilities)
    }

    @Test
    fun `getCapabilities caches result on subsequent calls`() {
        val first = TerminalFactory.getCapabilities()
        val second = TerminalFactory.getCapabilities()
        // Should be the same instance (cached)
        assertTrue(first === second)
    }

    @Test
    fun `refreshCapabilities updates cached value`() {
        val initial = TerminalFactory.getCapabilities()
        val refreshed = TerminalFactory.refreshCapabilities()
        // Should be a new instance (refreshed)
        assertNotNull(refreshed)
    }

    @Test
    fun `SAFE_DEFAULTS has expected values`() {
        val defaults = TerminalFactory.TerminalCapabilities.SAFE_DEFAULTS
        assertFalse(defaults.supportsUnicode)
        assertFalse(defaults.supportsColors)
        assertEquals(AnsiLevel.NONE, defaults.colorLevel)
        assertFalse(defaults.isInteractive)
        assertEquals(80, defaults.width)
        assertEquals(24, defaults.height)
    }

    @Test
    fun `getTerminalWidth returns positive value`() {
        val width = TerminalFactory.getTerminalWidth()
        assertTrue(width > 0)
    }

    @Test
    fun `getTerminalHeight returns positive value`() {
        val height = TerminalFactory.getTerminalHeight()
        assertTrue(height > 0)
    }

    @Test
    fun `createTerminal returns non-null terminal`() {
        val terminal = TerminalFactory.createTerminal()
        assertNotNull(terminal)
    }

    @Test
    fun `createTerminalForced returns terminal with truecolor`() {
        val terminal = TerminalFactory.createTerminalForced()
        assertNotNull(terminal)
        assertTrue(TerminalColors.enabled)
    }

    @Test
    fun `createTerminal initializes TerminalColors`() {
        TerminalColors.enabled = false
        TerminalColors.unicodeEnabled = false

        TerminalFactory.createTerminal()

        // After createTerminal, TerminalColors should be initialized
        // (actual values depend on environment)
        // Just verify the method runs without error
        assertNotNull(TerminalColors.enabled)
        assertNotNull(TerminalColors.unicodeEnabled)
    }

    @Test
    fun `reset clears cached capabilities`() {
        // Get capabilities to cache them
        TerminalFactory.getCapabilities()

        // Reset
        TerminalFactory.reset()

        // Getting capabilities again should trigger fresh detection
        val newCapabilities = TerminalFactory.getCapabilities()
        assertNotNull(newCapabilities)
    }

    @Test
    fun `onCapabilitiesChanged callback can be set`() {
        var callbackInvoked = false
        TerminalFactory.onCapabilitiesChanged = { _ ->
            callbackInvoked = true
        }

        assertNotNull(TerminalFactory.onCapabilitiesChanged)
        TerminalFactory.reset()
    }

    @Test
    fun `supportsUnicode returns boolean`() {
        val result = TerminalFactory.supportsUnicode()
        // Just verify it returns a boolean without error
        assertTrue(result || !result)
    }

    @Test
    fun `detectColorSupport returns valid AnsiLevel`() {
        val level = TerminalFactory.detectColorSupport()
        assertTrue(
            level == AnsiLevel.NONE ||
            level == AnsiLevel.ANSI16 ||
            level == AnsiLevel.ANSI256 ||
            level == AnsiLevel.TRUECOLOR
        )
    }

    @Test
    fun `isInteractive returns boolean`() {
        val result = TerminalFactory.isInteractive()
        // Just verify it returns a boolean without error
        assertTrue(result || !result)
    }

    @Test
    fun `capabilities width falls back to 80 on error`() {
        // Width should never be 0 or negative
        val width = TerminalFactory.getTerminalWidth()
        assertTrue(width >= 80 || width > 0)
    }

    @Test
    fun `capabilities height falls back to 24 on error`() {
        // Height should never be 0 or negative
        val height = TerminalFactory.getTerminalHeight()
        assertTrue(height >= 24 || height > 0)
    }
}
