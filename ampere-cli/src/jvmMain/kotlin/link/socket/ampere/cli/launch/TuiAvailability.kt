package link.socket.ampere.cli.launch

import link.socket.ampere.repl.TerminalFactory

/**
 * Decides whether the Lumos TUI dashboard is usable in the current environment,
 * or whether the CLI should fall back to plain-text headless output.
 *
 * The TUI is the default experience. We only fall back when:
 *   - The user explicitly asks for headless mode via `--headless`.
 *   - stdout is not a TTY (e.g. CI, piped runs).
 *   - The terminal is too small for the dashboard to be legible.
 */
data class TuiDecision(
    val useHeadless: Boolean,
    val reason: HeadlessReason?,
) {
    val useTui: Boolean get() = !useHeadless
}

enum class HeadlessReason {
    USER_REQUESTED,
    NON_TTY,
    TERMINAL_TOO_SMALL,
}

const val MIN_TUI_WIDTH: Int = 40
const val MIN_TUI_HEIGHT: Int = 15

fun decideTuiUsage(
    userRequestedHeadless: Boolean,
    capabilities: TerminalFactory.TerminalCapabilities,
    minWidth: Int = MIN_TUI_WIDTH,
    minHeight: Int = MIN_TUI_HEIGHT,
): TuiDecision = when {
    userRequestedHeadless -> TuiDecision(true, HeadlessReason.USER_REQUESTED)
    !capabilities.isInteractive -> TuiDecision(true, HeadlessReason.NON_TTY)
    capabilities.width < minWidth || capabilities.height < minHeight ->
        TuiDecision(true, HeadlessReason.TERMINAL_TOO_SMALL)
    else -> TuiDecision(false, null)
}

fun HeadlessReason.userMessage(
    capabilities: TerminalFactory.TerminalCapabilities,
    minWidth: Int = MIN_TUI_WIDTH,
    minHeight: Int = MIN_TUI_HEIGHT,
): String = when (this) {
    HeadlessReason.USER_REQUESTED ->
        "Running in headless mode (--headless)."
    HeadlessReason.NON_TTY ->
        "stdout is not a TTY; falling back to headless mode."
    HeadlessReason.TERMINAL_TOO_SMALL ->
        "Terminal is too small for the Lumos TUI " +
            "(${capabilities.width}x${capabilities.height}; need at least ${minWidth}x$minHeight); " +
            "falling back to headless mode."
}
