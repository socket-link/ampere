package link.socket.ampere.repl

import kotlinx.coroutines.*
import org.jline.terminal.Terminal

/**
 * Shows a progress spinner while a command is running.
 * Automatically stops when the command completes or is interrupted.
 */
class ProgressIndicator(
    private val terminal: Terminal
) {
    private val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    private var job: Job? = null

    /**
     * Start showing the spinner with a message.
     */
    fun start(message: String) {
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
