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

    // Ampere accent color: #24A6DF (RGB: 36, 166, 223)
    // Uses 24-bit true color ANSI escape sequence
    const val ACCENT = "\u001B[38;2;36;166;223m"

    /**
     * Enable/disable colors (can be toggled for testing or non-color terminals).
     */
    var enabled: Boolean = true

    fun success(message: String) = if (enabled) "$GREEN✓ $message$RESET" else "✓ $message"
    fun error(message: String) = if (enabled) "$RED✗ $message$RESET" else "✗ $message"
    fun info(message: String) = if (enabled) "$CYAN$message$RESET" else message
    fun warning(message: String) = if (enabled) "$YELLOW⚠ $message$RESET" else "⚠ $message"
    fun dim(message: String) = if (enabled) "$GRAY$message$RESET" else message
    fun emphasis(message: String) = if (enabled) "$BOLD$message$RESET" else message
    fun highlight(message: String) = if (enabled) "$BLUE$BOLD$message$RESET" else message
    fun accent(message: String) = if (enabled) "$ACCENT$message$RESET" else message
}
