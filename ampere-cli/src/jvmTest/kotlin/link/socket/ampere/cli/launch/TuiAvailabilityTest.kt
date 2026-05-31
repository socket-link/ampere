package link.socket.ampere.cli.launch

import com.github.ajalt.mordant.rendering.AnsiLevel
import link.socket.ampere.repl.TerminalFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuiAvailabilityTest {

    private fun caps(
        width: Int = 120,
        height: Int = 40,
        isInteractive: Boolean = true,
    ) = TerminalFactory.TerminalCapabilities(
        supportsUnicode = true,
        supportsColors = true,
        colorLevel = AnsiLevel.TRUECOLOR,
        isInteractive = isInteractive,
        width = width,
        height = height,
    )

    @Test
    fun `interactive terminal at full size keeps the TUI on`() {
        val decision = decideTuiUsage(userRequestedHeadless = false, capabilities = caps())
        assertTrue(decision.useTui)
        assertNull(decision.reason)
    }

    @Test
    fun `user --headless flag overrides everything else`() {
        val decision = decideTuiUsage(userRequestedHeadless = true, capabilities = caps())
        assertTrue(decision.useHeadless)
        assertEquals(HeadlessReason.USER_REQUESTED, decision.reason)
    }

    @Test
    fun `non-TTY auto-falls-back to headless`() {
        val decision = decideTuiUsage(
            userRequestedHeadless = false,
            capabilities = caps(isInteractive = false),
        )
        assertTrue(decision.useHeadless)
        assertEquals(HeadlessReason.NON_TTY, decision.reason)
    }

    @Test
    fun `terminal narrower than threshold falls back`() {
        val decision = decideTuiUsage(
            userRequestedHeadless = false,
            capabilities = caps(width = MIN_TUI_WIDTH - 1),
        )
        assertTrue(decision.useHeadless)
        assertEquals(HeadlessReason.TERMINAL_TOO_SMALL, decision.reason)
    }

    @Test
    fun `terminal shorter than threshold falls back`() {
        val decision = decideTuiUsage(
            userRequestedHeadless = false,
            capabilities = caps(height = MIN_TUI_HEIGHT - 1),
        )
        assertTrue(decision.useHeadless)
        assertEquals(HeadlessReason.TERMINAL_TOO_SMALL, decision.reason)
    }

    @Test
    fun `user-requested message reads as the default branch`() {
        val message = HeadlessReason.USER_REQUESTED.userMessage(caps())
        assertEquals("Running in headless mode (--headless).", message)
    }

    @Test
    fun `too-small message includes actual and required dimensions`() {
        val message = HeadlessReason.TERMINAL_TOO_SMALL.userMessage(caps(width = 20, height = 10))
        assertTrue("20x10" in message, "actual size missing: $message")
        assertTrue("${MIN_TUI_WIDTH}x$MIN_TUI_HEIGHT" in message, "threshold missing: $message")
    }
}
