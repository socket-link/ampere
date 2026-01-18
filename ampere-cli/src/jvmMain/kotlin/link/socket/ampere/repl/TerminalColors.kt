package link.socket.ampere.repl

/**
 * Terminal color utilities for consistent visual feedback.
 *
 * Uses raw ANSI codes for maximum compatibility across terminal emulators,
 * including IntelliJ IDEA, VS Code, iTerm2, and standard terminals.
 *
 * Provides semantic coloring for different types of output:
 * - Success: Green with ✓
 * - Error: Red with ✗
 * - Info: Cyan with ℹ
 * - Warning: Yellow with ⚠
 * - Dim: Gray for secondary information
 *
 * Automatically adapts symbols based on Unicode support detection.
 */
object TerminalColors {
    // ANSI color codes - compatible with all modern terminals
    private const val RESET = "\u001B[0m"
    private const val GREEN = "\u001B[32m"
    private const val RED = "\u001B[31m"
    private const val CYAN = "\u001B[36m"
    private const val YELLOW = "\u001B[33m"
    private const val BLUE = "\u001B[34m"
    private const val GRAY = "\u001B[37m"
    private const val BOLD = "\u001B[1m"

    // Unicode symbols
    private const val CHECK_UNICODE = "✓"
    private const val CROSS_UNICODE = "✗"
    private const val WARNING_UNICODE = "⚠"
    private const val INFO_UNICODE = "ℹ"

    // ASCII fallback symbols
    private const val CHECK_ASCII = "[OK]"
    private const val CROSS_ASCII = "[X]"
    private const val WARNING_ASCII = "[!]"
    private const val INFO_ASCII = "[i]"

    /**
     * Enable/disable colors (can be toggled for testing or non-color terminals).
     */
    var enabled: Boolean = true

    /**
     * Enable/disable Unicode symbols (falls back to ASCII if disabled).
     */
    var unicodeEnabled: Boolean = true

    // Symbol getters that respect Unicode setting
    private val checkSymbol: String get() = if (unicodeEnabled) CHECK_UNICODE else CHECK_ASCII
    private val crossSymbol: String get() = if (unicodeEnabled) CROSS_UNICODE else CROSS_ASCII
    private val warningSymbol: String get() = if (unicodeEnabled) WARNING_UNICODE else WARNING_ASCII
    private val infoSymbol: String get() = if (unicodeEnabled) INFO_UNICODE else INFO_ASCII

    /**
     * Initializes color and Unicode settings from detected terminal capabilities.
     * Called automatically by TerminalFactory.createTerminal().
     */
    fun initializeFromCapabilities(capabilities: TerminalFactory.TerminalCapabilities) {
        enabled = capabilities.supportsColors
        unicodeEnabled = capabilities.supportsUnicode
    }

    /**
     * Resets to default settings (colors and Unicode enabled).
     */
    fun reset() {
        enabled = true
        unicodeEnabled = true
    }

    fun success(message: String) = if (enabled) "$GREEN$checkSymbol $message$RESET" else "$checkSymbol $message"
    fun error(message: String) = if (enabled) "$RED$crossSymbol $message$RESET" else "$crossSymbol $message"
    fun info(message: String) = if (enabled) "$CYAN$message$RESET" else message
    fun infoWithSymbol(message: String) = if (enabled) "$CYAN$infoSymbol $message$RESET" else "$infoSymbol $message"
    fun warning(message: String) = if (enabled) "$YELLOW$warningSymbol $message$RESET" else "$warningSymbol $message"
    fun dim(message: String) = if (enabled) "$GRAY$message$RESET" else message
    fun emphasis(message: String) = if (enabled) "$BOLD$message$RESET" else message
    fun highlight(message: String) = if (enabled) "$BLUE$BOLD$message$RESET" else message
}
