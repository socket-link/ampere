package link.socket.ampere.repl

import com.github.ajalt.mordant.rendering.AnsiLevel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalCapabilityTest {

    @Test
    fun `supportsUnicode returns true for UTF-8 encoding`() = withSystemAccess(
        FakeSystemAccess(properties = mapOf("stdout.encoding" to "UTF-8"))
    ) {
        assertTrue(TerminalFactory.supportsUnicode())
    }

    @Test
    fun `supportsUnicode returns false for non-UTF encoding`() = withSystemAccess(
        FakeSystemAccess(properties = mapOf("stdout.encoding" to "US-ASCII"))
    ) {
        assertFalse(TerminalFactory.supportsUnicode())
    }

    @Test
    fun `supportsUnicode falls back to LANG when encoding is missing`() = withSystemAccess(
        FakeSystemAccess(env = mapOf("LANG" to "en_US.UTF-8"))
    ) {
        assertTrue(TerminalFactory.supportsUnicode())
    }

    @Test
    fun `detectColorSupport returns NONE when NO_COLOR is set`() = withSystemAccess(
        FakeSystemAccess(env = mapOf("NO_COLOR" to "1"), consoleAvailable = true)
    ) {
        assertEquals(AnsiLevel.NONE, TerminalFactory.detectColorSupport())
    }

    @Test
    fun `detectColorSupport returns ANSI256 for 256color TERM`() = withSystemAccess(
        FakeSystemAccess(env = mapOf("TERM" to "xterm-256color"), consoleAvailable = true)
    ) {
        assertEquals(AnsiLevel.ANSI256, TerminalFactory.detectColorSupport())
    }

    @Test
    fun `detectColorSupport returns ANSI16 for basic TERM`() = withSystemAccess(
        FakeSystemAccess(env = mapOf("TERM" to "xterm"), consoleAvailable = true)
    ) {
        assertEquals(AnsiLevel.ANSI16, TerminalFactory.detectColorSupport())
    }

    @Test
    fun `detectColorSupport returns NONE for dumb TERM`() = withSystemAccess(
        FakeSystemAccess(env = mapOf("TERM" to "dumb"), consoleAvailable = true)
    ) {
        assertEquals(AnsiLevel.NONE, TerminalFactory.detectColorSupport())
    }

    @Test
    fun `isInteractive returns false when console is unavailable`() = withSystemAccess(
        FakeSystemAccess(consoleAvailable = false)
    ) {
        assertFalse(TerminalFactory.isInteractive())
    }

    @Test
    fun `terminal size falls back to defaults when unavailable`() = withSystemAccess(
        FakeSystemAccess(sttySizeResult = null to null)
    ) {
        assertEquals(80, TerminalFactory.getTerminalWidth())
        assertEquals(24, TerminalFactory.getTerminalHeight())
    }

    private fun withSystemAccess(access: TerminalFactory.SystemAccess, block: () -> Unit) {
        TerminalFactory.reset()
        TerminalFactory.systemAccess = access
        try {
            block()
        } finally {
            TerminalFactory.reset()
        }
    }

    private class FakeSystemAccess(
        private val properties: Map<String, String?> = emptyMap(),
        private val env: Map<String, String?> = emptyMap(),
        private val consoleAvailable: Boolean = true,
        private val sttySizeResult: Pair<Int?, Int?> = null to null
    ) : TerminalFactory.SystemAccess {
        override fun getProperty(name: String): String? = properties[name]

        override fun getEnv(name: String): String? = env[name]

        override fun hasConsole(): Boolean = consoleAvailable

        override fun sttySize(): Pair<Int?, Int?> = sttySizeResult
    }
}
