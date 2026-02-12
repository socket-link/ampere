package link.socket.ampere.logging

import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.events.subscription.Subscription
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
     * Suppress event publish logs (no-op).
     */
    override fun logPublish(event: Event) {
        // Suppressed for quiet mode
    }

    /**
     * Suppress subscription logs (no-op).
     */
    override fun logSubscription(eventType: EventType, subscription: Subscription) {
        // Suppressed for quiet mode
    }

    /**
     * Suppress unsubscription logs (no-op).
     */
    override fun logUnsubscription(eventType: EventType, subscription: Subscription) {
        // Suppressed for quiet mode
    }

    /**
     * Still log errors to stderr (important for debugging).
     */
    override fun logError(message: String, throwable: Throwable?) {
        System.err.println("Error: $message")
        throwable?.printStackTrace()
    }

    /**
     * Suppress info messages (no-op).
     */
    override fun logInfo(message: String) {
        // Suppressed for quiet mode
    }
}
