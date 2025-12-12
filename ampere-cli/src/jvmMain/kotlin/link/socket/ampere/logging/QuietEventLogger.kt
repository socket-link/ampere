package link.socket.ampere.logging

import link.socket.ampere.agents.events.utils.EventLogger

/**
 * Event logger that suppresses info messages for demo-friendly startup.
 *
 * This logger only outputs errors, keeping the CLI clean and focused
 * on user-facing output rather than system diagnostics.
 *
 * Use ConsoleEventLogger with --verbose flag for full diagnostic output.
 */
class QuietEventLogger : EventLogger {
    /**
     * Suppress info messages (no-op).
     */
    override fun logInfo(message: String) {
        // Suppressed for quiet mode
    }

    /**
     * Still log errors to stderr (important for debugging).
     */
    override fun logError(message: String) {
        System.err.println("Error: $message")
    }
}
