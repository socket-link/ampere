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
     * System access abstraction for testability.
     */
    internal interface SystemAccess {
        fun getProperty(name: String): String?
        fun getEnv(name: String): String?
        fun hasConsole(): Boolean
        fun sttySize(): Pair<Int?, Int?>
    }

    /**
     * Default system access implementation.
     */
    internal object DefaultSystemAccess : SystemAccess {
        override fun getProperty(name: String): String? = System.getProperty(name)

        override fun getEnv(name: String): String? = System.getenv(name)

        override fun hasConsole(): Boolean = System.console() != null

        override fun sttySize(): Pair<Int?, Int?> {
            return try {
                val process = ProcessBuilder("stty", "size")
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()

                if (process.exitValue() == 0) {
                    val parts = output.split(" ")
                    val lines = parts.getOrNull(0)?.toIntOrNull()
                    val columns = parts.getOrNull(1)?.toIntOrNull()
                    lines to columns
                } else {
                    null to null
                }
            } catch (e: Exception) {
                null to null
            }
        }
    }

    @Volatile
    internal var systemAccess: SystemAccess = DefaultSystemAccess

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
            val encoding = systemAccess.getProperty("stdout.encoding")
                ?: systemAccess.getProperty("file.encoding")
                ?: systemAccess.getEnv("LANG")
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
            if (systemAccess.getEnv("NO_COLOR") != null) {
                return AnsiLevel.NONE
            }

            // Check if we have a console
            if (!systemAccess.hasConsole()) {
                // No console - might be piped, check FORCE_COLOR
                if (systemAccess.getEnv("FORCE_COLOR") != null) {
                    return AnsiLevel.TRUECOLOR
                }
                return AnsiLevel.NONE
            }

            // Check TERM environment variable
            val term = systemAccess.getEnv("TERM") ?: ""
            val colorTerm = systemAccess.getEnv("COLORTERM") ?: ""

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
        return systemAccess.hasConsole()
    }

    /**
     * Gets the terminal width with safe default fallback.
     * Falls back to 80 characters on detection failure.
     */
    fun getTerminalWidth(): Int {
        return try {
            val columns = systemAccess.getEnv("COLUMNS")?.toIntOrNull()
            if (columns != null && columns > 0) {
                return columns
            }

            val (_, sttyColumns) = systemAccess.sttySize()
            if (sttyColumns != null && sttyColumns > 0) {
                sttyColumns
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
            val lines = systemAccess.getEnv("LINES")?.toIntOrNull()
            if (lines != null && lines > 0) {
                return lines
            }

            val (sttyLines, _) = systemAccess.sttySize()
            if (sttyLines != null && sttyLines > 0) {
                sttyLines
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

        // Update TerminalColors and TerminalSymbols based on detected capabilities
        TerminalColors.initializeFromCapabilities(capabilities)
        TerminalSymbols.initializeFromCapabilities(capabilities)

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
        systemAccess = DefaultSystemAccess
    }
}
