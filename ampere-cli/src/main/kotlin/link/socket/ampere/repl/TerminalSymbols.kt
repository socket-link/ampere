package link.socket.ampere.repl

/**
 * Centralized terminal symbols with automatic Unicode/ASCII fallback.
 *
 * Provides consistent symbols across all CLI components that automatically
 * adapt based on terminal capabilities detected by [TerminalFactory].
 *
 * Symbol categories:
 * - Status indicators (●, ○, ✓, ✗, etc.)
 * - Spinners (braille dots for Unicode, rotating chars for ASCII)
 * - Box drawing (┌, ─, ┘, etc.)
 * - Arrows (──▶, ◀──)
 * - Lightning animation frames
 *
 * Usage:
 * ```kotlin
 * val spinner = TerminalSymbols.spinner
 * val check = TerminalSymbols.status.check
 * ```
 */
object TerminalSymbols {

    /**
     * Whether to use Unicode symbols. Automatically set from terminal capabilities
     * but can be manually overridden for testing.
     */
    var useUnicode: Boolean = true
        private set

    /**
     * Whether the terminal is interactive (not piped).
     */
    var isInteractive: Boolean = true
        private set

    /**
     * Initialize symbols from detected terminal capabilities.
     * Called automatically by TerminalFactory.createTerminal().
     */
    fun initializeFromCapabilities(capabilities: TerminalFactory.TerminalCapabilities) {
        useUnicode = capabilities.supportsUnicode
        isInteractive = capabilities.isInteractive
    }

    /**
     * Force ASCII mode for testing or explicit override.
     */
    fun forceAscii() {
        useUnicode = false
    }

    /**
     * Force non-interactive mode.
     */
    fun forceNonInteractive() {
        isInteractive = false
    }

    /**
     * Reset to defaults (Unicode enabled, interactive).
     */
    fun reset() {
        useUnicode = true
        isInteractive = true
    }

    // ============================================================
    // Status Indicators
    // ============================================================

    object Status {
        val check: String get() = if (useUnicode) "✓" else "[OK]"
        val cross: String get() = if (useUnicode) "✗" else "[X]"
        val warning: String get() = if (useUnicode) "⚠" else "[!]"
        val info: String get() = if (useUnicode) "ℹ" else "[i]"

        val filledCircle: String get() = if (useUnicode) "●" else "o"
        val emptyCircle: String get() = if (useUnicode) "○" else "."
        val halfCircle: String get() = if (useUnicode) "◐" else "o"
        val halfCircle2: String get() = if (useUnicode) "◓" else "O"
        val halfCircle3: String get() = if (useUnicode) "◑" else "o"
        val halfCircle4: String get() = if (useUnicode) "◒" else "O"
        val dotCircle: String get() = if (useUnicode) "◌" else "o"

        val lightning: String get() = if (useUnicode) "⚡" else "*"
    }

    // ============================================================
    // Spinner Frames
    // ============================================================

    object Spinner {
        /** Braille-based spinner for Unicode terminals. */
        val unicodeFrames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

        /** Classic ASCII spinner. */
        val asciiFrames = listOf("-", "\\", "|", "/")

        /** Half-circle spinner for topology. */
        val halfCircleUnicode = listOf("◐", "◓", "◑", "◒")
        val halfCircleAscii = listOf("-", "\\", "|", "/")

        /** Get spinner frames based on current mode. */
        val frames: List<String>
            get() = if (useUnicode) unicodeFrames else asciiFrames

        /** Get half-circle spinner frames based on current mode. */
        val halfCircleFrames: List<String>
            get() = if (useUnicode) halfCircleUnicode.map { it } else halfCircleAscii

        /** Static indicator for non-interactive mode. */
        const val staticIndicator = "[*]"
    }

    // ============================================================
    // Box Drawing Characters
    // ============================================================

    object Box {
        val topLeft: String get() = if (useUnicode) "┌" else "+"
        val topRight: String get() = if (useUnicode) "┐" else "+"
        val bottomLeft: String get() = if (useUnicode) "└" else "+"
        val bottomRight: String get() = if (useUnicode) "┘" else "+"
        val horizontal: String get() = if (useUnicode) "─" else "-"
        val vertical: String get() = if (useUnicode) "│" else "|"
        val horizontalDashed: String get() = if (useUnicode) "╌" else "-"
        val verticalDashed: String get() = if (useUnicode) "╎" else "|"
    }

    // ============================================================
    // Arrows and Connectors
    // ============================================================

    object Arrow {
        val right: String get() = if (useUnicode) "▶" else ">"
        val left: String get() = if (useUnicode) "◀" else "<"
        val forward: String get() = if (useUnicode) "──▶" else "-->"
        val backward: String get() = if (useUnicode) "◀──" else "<--"
        val rightThin: String get() = if (useUnicode) "→" else "->"
        val leftThin: String get() = if (useUnicode) "←" else "<-"
    }

    // ============================================================
    // Lightning Animation Frames
    // ============================================================

    object Lightning {
        /** Full discharge sequence - Unicode version. */
        val dischargeUnicode = listOf(
            "·",   // ground state
            "˙",   // charge
            ":",   // stepped leader
            "⁝",   // building
            "⁞",   // intensifying
            "│",   // channel
            "╽",   // near strike
            "ϟ",   // strike!
            "✦",   // afterglow
            "✧",   // fading
            "·"    // rest
        )

        /** Full discharge sequence - ASCII fallback. */
        val dischargeAscii = listOf(
            ".",   // ground state
            ".",   // charge
            ":",   // stepped leader
            ":",   // building
            "|",   // intensifying
            "|",   // channel
            "|",   // near strike
            "#",   // strike!
            "*",   // afterglow
            "+",   // fading
            "."    // rest
        )

        /** Compact sequence - Unicode version. */
        val compactUnicode = listOf("·", ":", "⁞", "ϟ", "✧", "·")

        /** Compact sequence - ASCII fallback. */
        val compactAscii = listOf(".", ":", "|", "#", "+", ".")

        /** Get discharge symbols based on current mode. */
        val discharge: List<String>
            get() = if (useUnicode) dischargeUnicode else dischargeAscii

        /** Get compact symbols based on current mode. */
        val compact: List<String>
            get() = if (useUnicode) compactUnicode else compactAscii

        /** Static lightning symbol for non-interactive mode. */
        val staticSymbol: String get() = if (useUnicode) "⚡" else "*"
    }

    // ============================================================
    // Block Characters (for progress bars, etc.)
    // ============================================================

    object Block {
        val full: String get() = if (useUnicode) "█" else "#"
        val light: String get() = if (useUnicode) "░" else "-"
        val medium: String get() = if (useUnicode) "▒" else "="
        val dark: String get() = if (useUnicode) "▓" else "#"
    }

    // ============================================================
    // Separator Characters
    // ============================================================

    object Separator {
        val vertical: String get() = if (useUnicode) "│" else "|"
        val bullet: String get() = if (useUnicode) "•" else "*"
        val dot: String get() = if (useUnicode) "·" else "."
    }
}
