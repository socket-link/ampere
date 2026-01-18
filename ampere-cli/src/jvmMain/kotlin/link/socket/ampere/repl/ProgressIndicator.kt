package link.socket.ampere.repl

import kotlinx.coroutines.*
import org.jline.terminal.Terminal

/**
 * Shows a progress spinner while a command is running.
 * Automatically stops when the command completes or is interrupted.
 *
 * Adapts to terminal capabilities:
 * - Unicode terminals: Uses braille spinner (⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏)
 * - ASCII terminals: Uses classic spinner (- \ | /)
 * - Non-interactive: Shows static "[*] Loading..." without animation
 */
class ProgressIndicator(
    private val terminal: Terminal
) {
    private var job: Job? = null

    /**
     * Start showing the spinner with a message.
     *
     * In non-interactive mode (piped output), displays a static message
     * without animation to avoid cluttering output with cursor control codes.
     */
    fun start(message: String) {
        if (!TerminalSymbols.isInteractive) {
            // Non-interactive mode: print static indicator once
            terminal.writer().println("${TerminalSymbols.Spinner.staticIndicator} $message")
            terminal.writer().flush()
            return
        }

        val frames = TerminalSymbols.Spinner.frames
        job = CoroutineScope(Dispatchers.Default).launch {
            var frameIndex = 0
            while (isActive) {
                terminal.writer().print("\r${frames[frameIndex]} $message")
                terminal.writer().flush()
                frameIndex = (frameIndex + 1) % frames.size
                delay(100)
            }
            // Clear the spinner line
            terminal.writer().print("\r" + " ".repeat(message.length + 3) + "\r")
            terminal.writer().flush()
        }
    }

    /**
     * Stop the spinner.
     */
    fun stop() {
        job?.cancel()
        job = null
    }
}
