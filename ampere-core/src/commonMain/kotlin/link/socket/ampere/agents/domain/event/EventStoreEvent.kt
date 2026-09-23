package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.utils.EventSerializationException

/**
 * Signals about the event store itself, from the door that writes to it (AMPR-301).
 *
 * Lives in `agents.domain.event` because [Event] is sealed: every subtype has to share its
 * module and package.
 */
@Serializable
sealed interface EventStoreEvent : Event {

    /**
     * The store refused a write, so an event that happened has no durable record of happening.
     *
     * This is the one event kind that is **never persisted**. It is dispatched straight onto
     * the bus by `AgentEventApi.publish`, bypassing the door's persist-then-dispatch order,
     * because writing it would go through the very store that just failed — and on the failure
     * this exists for, `SQLITE_FULL`, that write cannot succeed by definition. A live signal
     * that something was lost is worth more than a durable record that cannot be written.
     *
     * Its counterpart for consumers that want to observe the store rather than the bus is
     * `EventRepository.signals`, which carries the same failure plus the truncation and pruning
     * the store handles on its own.
     *
     * @property failedEventId the id of the event whose write failed — the event that the Field
     * now has no row for.
     * @property failedEventType that event's type, so a consumer can tell a lost `TaskCompleted`
     * from a lost telemetry ping without decoding anything.
     * @property failure the classification the store put on the throwable; see [EventStoreFailure].
     * @property reason the failure's own message, verbatim, for the operator reading the pane.
     */
    @Serializable
    @SerialName("EventStoreEvent.PersistenceFailed")
    data class PersistenceFailed(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val failedEventId: EventId,
        val failedEventType: EventType,
        val failure: EventStoreFailure,
        val reason: String,
        override val urgency: Urgency = Urgency.HIGH,
    ) : EventStoreEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Event store rejected $failedEventType ($failedEventId): ${failure.description}")
            if (reason.isNotBlank()) append(" — $reason")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "EventStorePersistenceFailed"
        }
    }
}

/**
 * Why an `EventStore` write failed, as far as the store can tell (AMPR-301).
 *
 * [STORAGE_FULL] is the case the AMPR-291 fate table singled out — it is not transient, it
 * affects every subsequent write, and it is the one an operator has to act on — so it is worth
 * separating from the rest even though the separation is made by reading a driver's message.
 */
@Serializable
enum class EventStoreFailure(val description: String) {

    /** The disk or the database is full. Every later write will fail the same way. */
    STORAGE_FULL("storage is full"),

    /** The event could not be serialized, so no write was attempted. Affects this event only. */
    SERIALIZATION("event could not be serialized"),

    /** Anything else — a locked database, a closed driver, an I/O error. */
    OTHER("write failed"),
    ;

    companion object {

        /**
         * Message fragments every SQLite binding this project drives (JDBC, Android, Native)
         * uses for `SQLITE_FULL`. Matched case-insensitively.
         *
         * Matching on text rather than on an error code is deliberate: the code lives on a
         * driver-specific exception type in each platform source set, and the classification is
         * only used to label a signal — a misclassified failure still surfaces, as [OTHER].
         */
        private val STORAGE_FULL_MARKERS = listOf(
            "sqlite_full",
            "database or disk is full",
            "disk is full",
            "no space left on device",
        )

        /**
         * Classifies [throwable] and everything that caused it. The walk remembers what it
         * has seen, because a cyclic cause chain would otherwise turn a failed write into a
         * hung one.
         */
        fun classify(throwable: Throwable): EventStoreFailure {
            val seen = mutableSetOf<Throwable>()
            var current: Throwable? = throwable
            while (current != null) {
                if (!seen.add(current)) return OTHER
                val text = current.message?.lowercase().orEmpty()
                if (STORAGE_FULL_MARKERS.any { marker -> marker in text }) return STORAGE_FULL
                if (current is EventSerializationException) return SERIALIZATION
                current = current.cause
            }
            return OTHER
        }
    }
}
