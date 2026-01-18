package link.socket.ampere.repl

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import sun.misc.Signal
import sun.misc.SignalHandler

/**
 * Factory for creating Mordant Terminal instances with proper configuration
 * for maximum compatibility across terminal emulators.
 *
 * Detects terminal capabilities and provides safe defaults:
 * - Unicode support detection via encoding
 * - Color support via TERM variable and NO_COLOR env
 * - Interactive mode via System.console()
 * - Dynamic refresh on terminal resize (SIGWINCH)
 *
 * Forces ANSI color support to ensure colors display correctly in:
 * - IntelliJ IDEA terminal
 * - VS Code integrated terminal
 * - iTerm2
 * - macOS Terminal.app
 * - Standard Linux terminals
 */
object TerminalFactory {
    /**
     * Cached terminal capabilities, detected at startup and refreshed on SIGWINCH.
     */
    data class TerminalCapabilities(
        val supportsUnicode: Boolean,
        val supportsColors: Boolean,
        val colorLevel: AnsiLevel,
        val isInteractive: Boolean,
        val width: Int,
        val height: Int
    ) {
        companion object {
            /**
             * Safe defaults for unknown or unsupported terminals.
             */
            val SAFE_DEFAULTS = TerminalCapabilities(
                supportsUnicode = false,
                supportsColors = false,
                colorLevel = AnsiLevel.NONE,
                isInteractive = false,
                width = 80,
                height = 24
            )
        }
    }

    // Cached capabilities, initialized lazily on first access
    @Volatile
    private var cachedCapabilities: TerminalCapabilities? = null

    // SIGWINCH handler for dynamic refresh
    private var sigwinchHandler: SignalHandler? = null

    /**
     * Gets the current terminal capabilities, detecting them if not yet cached.
     */
    fun getCapabilities(): TerminalCapabilities {
        return cachedCapabilities ?: detectCapabilities().also {
            cachedCapabilities = it
            installSigwinchHandler()
        }
    }

    /**
     * Forces a refresh of cached terminal capabilities.
     * Call this if terminal settings may have changed.
     */
    fun refreshCapabilities(): TerminalCapabilities {
        return detectCapabilities().also {
            cachedCapabilities = it
        }
    }

    /**
     * Detects whether the terminal supports Unicode characters.
     * Checks encoding for UTF compatibility (UTF-8, UTF-16, etc.)
     */
    fun supportsUnicode(): Boolean {
        return try {
            val encoding = System.getProperty("stdout.encoding")
                ?: System.getProperty("file.encoding")
                ?: System.getenv("LANG")
                ?: ""
            encoding.uppercase().let {
                it.contains("UTF") || it.contains("UNICODE")
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Detects whether the terminal supports ANSI colors.
     * Checks TERM variable, NO_COLOR env, and console availability.
     *
     * @return The detected ANSI color level
     */
    fun detectColorSupport(): AnsiLevel {
        return try {
            // Check NO_COLOR environment variable (https://no-color.org/)
            if (System.getenv("NO_COLOR") != null) {
                return AnsiLevel.NONE
            }

            // Check if we have a console
            if (System.console() == null) {
                // No console - might be piped, check FORCE_COLOR
                if (System.getenv("FORCE_COLOR") != null) {
                    return AnsiLevel.TRUECOLOR
                }
                return AnsiLevel.NONE
            }

            // Check TERM environment variable
            val term = System.getenv("TERM") ?: ""
            val colorTerm = System.getenv("COLORTERM") ?: ""

            // Check for 24-bit color support
            if (colorTerm == "truecolor" || colorTerm == "24bit") {
                return AnsiLevel.TRUECOLOR
            }

            // Check for 256-color support
            if (term.contains("256color") || term.contains("256-color")) {
                return AnsiLevel.ANSI256
            }

            // Check for basic color support
            val colorTerminals = listOf(
                "xterm", "vt100", "screen", "tmux", "linux",
                "cygwin", "ansi", "rxvt", "konsole", "gnome"
            )
            if (colorTerminals.any { term.startsWith(it) }) {
                return AnsiLevel.ANSI16
            }

            // Check for dumb terminal (no color support)
            if (term == "dumb") {
                return AnsiLevel.NONE
            }

            // Default to TRUECOLOR for modern compatibility
            AnsiLevel.TRUECOLOR
        } catch (e: Exception) {
            AnsiLevel.NONE
        }
    }

    /**
     * Detects whether the terminal is running in interactive mode.
     * Uses System.console() to check for a connected terminal.
     */
    fun isInteractive(): Boolean {
        return System.console() != null
    }

    /**
     * Gets the terminal width with safe default fallback.
     * Falls back to 80 characters on detection failure.
     */
    fun getTerminalWidth(): Int {
        return try {
            val columns = System.getenv("COLUMNS")?.toIntOrNull()
            if (columns != null && columns > 0) {
                return columns
            }

            // Try to get from stty (Unix-like systems)
            val process = ProcessBuilder("stty", "size")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            if (process.exitValue() == 0) {
                output.split(" ").getOrNull(1)?.toIntOrNull() ?: 80
            } else {
                80
            }
        } catch (e: Exception) {
            80 // Safe default
        }
    }

    /**
     * Gets the terminal height with safe default fallback.
     * Falls back to 24 rows on detection failure.
     */
    fun getTerminalHeight(): Int {
        return try {
            val lines = System.getenv("LINES")?.toIntOrNull()
            if (lines != null && lines > 0) {
                return lines
            }

            // Try to get from stty (Unix-like systems)
            val process = ProcessBuilder("stty", "size")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            if (process.exitValue() == 0) {
                output.split(" ").getOrNull(0)?.toIntOrNull() ?: 24
            } else {
                24
            }
        } catch (e: Exception) {
            24 // Safe default
        }
    }

    /**
     * Detects all terminal capabilities and returns a TerminalCapabilities object.
     */
    private fun detectCapabilities(): TerminalCapabilities {
        return try {
            val colorLevel = detectColorSupport()
            TerminalCapabilities(
                supportsUnicode = supportsUnicode(),
                supportsColors = colorLevel != AnsiLevel.NONE,
                colorLevel = colorLevel,
                isInteractive = isInteractive(),
                width = getTerminalWidth(),
                height = getTerminalHeight()
            )
        } catch (e: Exception) {
            TerminalCapabilities.SAFE_DEFAULTS
        }
    }

    /**
     * Installs a SIGWINCH handler to refresh capabilities on terminal resize.
     * This is a no-op on systems that don't support SIGWINCH.
     */
    private fun installSigwinchHandler() {
        try {
            if (sigwinchHandler == null) {
                sigwinchHandler = SignalHandler { _ ->
                    // Refresh width/height on resize
                    cachedCapabilities = cachedCapabilities?.copy(
                        width = getTerminalWidth(),
                        height = getTerminalHeight()
                    )
                    // Notify listeners if any
                    onCapabilitiesChanged?.invoke(cachedCapabilities!!)
                }
                Signal.handle(Signal("WINCH"), sigwinchHandler)
            }
        } catch (e: Exception) {
            // SIGWINCH not supported on this platform, ignore
        }
    }

    /**
     * Optional callback for when capabilities change (e.g., on terminal resize).
     */
    var onCapabilitiesChanged: ((TerminalCapabilities) -> Unit)? = null

    /**
     * Creates a Mordant Terminal with detected ANSI support for color output.
     * Uses cached capabilities if available.
     */
    fun createTerminal(): Terminal {
        val capabilities = getCapabilities()

        // Update TerminalColors based on detected capabilities
        TerminalColors.initializeFromCapabilities(capabilities)

        return Terminal(
            ansiLevel = capabilities.colorLevel
        )
    }

    /**
     * Creates a Mordant Terminal with forced ANSI support.
     * Use this when you know colors are supported despite detection.
     */
    fun createTerminalForced(): Terminal {
        TerminalColors.enabled = true
        return Terminal(
            ansiLevel = AnsiLevel.TRUECOLOR
        )
    }

    /**
     * Clears cached capabilities and removes signal handlers.
     * Useful for testing or when resetting terminal state.
     */
    fun reset() {
        cachedCapabilities = null
        sigwinchHandler = null
        onCapabilitiesChanged = null
    }
}
