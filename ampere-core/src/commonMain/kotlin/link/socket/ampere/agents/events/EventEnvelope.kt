package link.socket.ampere.agents.events

import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventId

/**
 * What the door assigns to an event at publish time that the event itself never carries.
 *
 * The Field is a fold of persisted events; these are the row-level fields around a serialized
 * [Event] (F2, F4). Keeping them on the envelope rather than the event type means none of the
 * concrete `Event` classes change.
 *
 * @property causedBy the event whose handling produced this one, if any. This is the carrier
 * for provenance from a Belief back to the Event that produced it.
 * @property runId the Arc run this event belongs to. When null the repository falls back to
 * the deprecated per-kind lookup until every publisher passes it explicitly.
 */
data class EventEnvelope(
    val causedBy: EventId? = null,
    val runId: RunId? = null,
)

/**
 * A persisted [Event] together with its envelope as the store recorded it.
 *
 * @property sequence monotonic, unique, assigned inside the insert transaction. This — not
 * [Event.timestamp] — is the fold order.
 * @property recordedAt the door's clock at publish; [Event.timestamp] is whatever the publisher
 * put on the event and is left untouched.
 * @property truncated whether the stored payload's oversized string leaves were cut to fit
 * [EventStoreBudget.MAX_EVENT_BYTES] (AMPR-301). [event] is the caller's own object and is
 * never the truncated one — this says only that what the *row* holds is shorter than it was.
 */
data class StoredEvent(
    val event: Event,
    val sequence: Long,
    val recordedAt: Instant,
    val causedBy: EventId?,
    val runId: RunId?,
    val truncated: Boolean = false,
)
