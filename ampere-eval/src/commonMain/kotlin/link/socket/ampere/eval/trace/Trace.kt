package link.socket.ampere.eval.trace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single captured bus event, frozen into an ordered, serializable form.
 *
 * @property index zero-based position of this event within its [Trace], in emission order.
 * @property timestamp epoch milliseconds at which the source event occurred.
 * @property type the bus event-type discriminator (the source `Event.eventType`),
 *   used for human/queryable identification — NOT the kotlinx `"type"` payload
 *   discriminator (which lives inside [payload]).
 * @property payload the full serialized source event as a [JsonElement]; decodable
 *   back to the original `Event` via `Event.serializer()` with the shared `DEFAULT_JSON`.
 *   String leaves beyond [TraceBudget.MAX_STRING_FIELD_CHARS] are cut in place
 *   (see `truncateStringLeaves`) when the event exceeds [TraceBudget.MAX_EVENT_BYTES];
 *   the JSON shape is never changed, so this always remains decodable.
 * @property truncated true if [payload] was cut to fit [TraceBudget.MAX_EVENT_BYTES] (AMPR-267).
 */
@Serializable
data class TraceEvent(
    val index: Int,
    val timestamp: Long,
    val type: String,
    val payload: JsonElement,
    val truncated: Boolean = false,
)

/**
 * An ordered, serializable capture of a single run's `EventSerialBus` stream.
 *
 * A `Trace` is the one measurement primitive the eval set is built on: evals,
 * regression gates, and the later reward function are all consumers of a captured,
 * replayable event stream. Replay is handled by [TraceCursor].
 *
 * @property id unique identifier for this trace.
 * @property runId the run this trace was recorded for (see RECON-trace.md §3).
 * @property arcId the orchestration pathway (Arc) this run belongs to.
 * @property createdAt epoch milliseconds when the trace was recorded.
 * @property events the captured events, in emission order.
 * @property droppedEventCount trailing events cut when the trace exceeded
 *   [TraceBudget.MAX_TRACE_BYTES] (AMPR-267's drop-with-a-marker policy).
 *   Zero for a trace that stayed within budget.
 */
@Serializable
data class Trace(
    val id: String,
    val runId: String,
    val arcId: String,
    val createdAt: Long,
    val events: List<TraceEvent>,
    val droppedEventCount: Int = 0,
) {
    /** Number of events in the trace. */
    val size: Int get() = events.size

    /** Events `0..index` inclusive. Delegates to [List.take], so out-of-range is safe. */
    fun upTo(index: Int): List<TraceEvent> = events.take(index + 1)
}

/**
 * Queryable metadata for a persisted [Trace] without its event blob.
 * Returned by `TraceService.list`.
 */
@Serializable
data class TraceSummary(
    val id: String,
    val runId: String,
    val arcId: String,
    val createdAt: Long,
    val eventCount: Int,
    val droppedEventCount: Int = 0,
)
