package link.socket.ampere.field

import kotlinx.datetime.Instant

/**
 * Where a Field record came from: the event that produced it, and when.
 *
 * Provenance lives on the event *envelope*, not on the event type (F2): the envelope
 * carries `sequence`, `caused_by` and `recorded_at`, and every primitive points back at
 * it with the one field that cannot be recovered any other way — the id of the event
 * whose fold step created the record. From that id the whole causal chain is a walk
 * over `caused_by`; nothing here duplicates it.
 *
 * @property createdBy The `Event.eventId` (a UUID string) of the producing event. Typed
 *   as `String` rather than the `EventId` alias because the alias is declared in
 *   `agents.domain.event`, which this leaf package may not import; `CanonProvenance`
 *   makes the same trade for `perceiveEventId` (F7).
 * @property createdAt When the record was created. Bound once, from the envelope's
 *   `recorded_at`; never re-stamped on fold or read.
 */
data class Provenance(
    val createdBy: String,
    val createdAt: Instant,
)
