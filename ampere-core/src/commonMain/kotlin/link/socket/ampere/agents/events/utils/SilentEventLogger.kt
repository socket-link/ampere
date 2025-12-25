package link.socket.ampere.agents.events.utils

import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.events.subscription.Subscription

/**
 * Silent event logger that does nothing.
 * Useful for scenarios where you want to suppress event bus logging,
 * such as in UI dashboards or when running in production mode.
 */
class SilentEventLogger : EventLogger {
    override fun logPublish(event: Event) {
        // No-op
    }

    override fun logSubscription(eventType: EventType, subscription: Subscription) {
        // No-op
    }

    override fun logUnsubscription(eventType: EventType, subscription: Subscription) {
        // No-op
    }

    override fun logError(message: String, throwable: Throwable?) {
        // No-op - errors are still logged via the main application logger
    }

    override fun logInfo(message: String) {
        // No-op
    }
}
