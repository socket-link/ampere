package link.socket.ampere.agents.events.utils

import co.touchlab.kermit.Logger
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.events.subscription.Subscription

/**
 * Lightweight logger interface for EventBus diagnostics.
 */
interface EventLogger {
    /** Called whenever an event is published. */
    fun logPublish(event: Event)

    /** Called when a subscription occurs. */
    fun logSubscription(eventType: EventType, subscription: Subscription)

    /** Called when an unsubscription occurs. */
    fun logUnsubscription(eventType: EventType, subscription: Subscription)

    /** Log an error without crashing the app. */
    fun logError(message: String, throwable: Throwable? = null)

    /** Log an informational message. */
    fun logInfo(message: String)
}

/**
 * Basic console logger that prints structured messages using Kermit.
 */
class ConsoleEventLogger : EventLogger {

    private val logger = Logger.withTag("EventBus")

    override fun logPublish(event: Event) {
        logger.d {
            "[PUBLISH] type=${event.eventType} id=${event.eventId} ts=${event.timestamp} src=${event.eventSource.getIdentifier()}"
        }
    }

    override fun logSubscription(eventType: EventType, subscription: Subscription) {
        logger.d { "[SUBSCRIPTION] type=$eventType subscription=$subscription" }
    }

    override fun logUnsubscription(eventType: EventType, subscription: Subscription) {
        logger.d { "[UNSUBSCRIPTION] type=$eventType subscription=$subscription" }
    }

    override fun logError(message: String, throwable: Throwable?) {
        val errorMessage = "$message" + (
            throwable?.let {
                ": ${it::class.simpleName} - ${it.message}"
            } ?: ""
        )
        logger.e(throwable) { errorMessage }
    }

    override fun logInfo(message: String) {
        logger.i { message }
    }
}
