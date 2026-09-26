package link.socket.ampere.agents.events

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventStoreFailure
import link.socket.ampere.agents.domain.event.EventType

/**
 * Something the event store did to the record that the record itself cannot show (AMPR-301).
 *
 * Emitted on `EventRepository.signals`. Each case is a place where what the Field holds and
 * what actually happened have come apart: a write refused, a payload cut, rows rotated out.
 * The store leaves a durable marker for the two it can ([PayloadTruncated] sets `truncated` on
 * the row, [EventsPruned] writes an `EventStorePruning` record); the signal is how a live
 * consumer — a CLI status line, a supervisor, a test — learns about it without polling.
 *
 * Deliberately not an [link.socket.ampere.agents.domain.event.Event]. These are emitted from
 * inside `saveEvent`, and an `Event` emitted from there would have to be persisted by the same
 * call that is reporting on persistence. `EventStoreEvent.PersistenceFailed` is the bus-facing
 * counterpart of [PersistenceFailed], dispatched by the door rather than the store.
 */
sealed interface EventStoreSignal {

    /**
     * A write was refused. The event is not in the store and — since the door only dispatches
     * after a successful write — was not published either.
     */
    data class PersistenceFailed(
        val eventId: EventId,
        val eventType: EventType,
        val failure: EventStoreFailure,
        val reason: String,
    ) : EventStoreSignal

    /**
     * A payload exceeded [EventStoreBudget.MAX_EVENT_BYTES] and its oversized string leaves
     * were cut before the row was written. The row is flagged `truncated`; the JSON shape is
     * untouched, so it still decodes.
     */
    data class PayloadTruncated(
        val eventId: EventId,
        val eventType: EventType,
        val originalBytes: Int,
        val storedBytes: Int,
    ) : EventStoreSignal

    /**
     * The store passed [EventStoreBudget.MAX_STORE_BYTES] and the oldest rows were rotated out
     * down to [EventStoreBudget.PRUNE_TO_BYTES]. The same numbers are written to
     * `EventStorePruning`, so the drop is recoverable after the process exits.
     *
     * @property firstSequence lowest `sequence` dropped — everything below it was already gone.
     * @property lastSequence highest `sequence` dropped; the store now begins after it.
     */
    data class EventsPruned(
        val prunedAt: Instant,
        val droppedCount: Long,
        val droppedBytes: Long,
        val firstSequence: Long,
        val lastSequence: Long,
        val storeBytesBefore: Long,
        val storeBytesAfter: Long,
    ) : EventStoreSignal
}
