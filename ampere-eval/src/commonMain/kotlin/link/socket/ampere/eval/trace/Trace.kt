package link.socket.ampere.eval.trace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.trace.ReplayWindow

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
 * A [TraceEvent] this build could not decode back into an `Event`, kept as a counted fact
 * rather than an exception (AMPR-363).
 *
 * Unknown-type tolerance is an **envelope** property: the envelope preserves the payload
 * opaquely, counts what it could not read, and keeps going. It is never bought inside
 * `Event` or `CanonEntity` decode — a `defaultDeserializer` there would not recover the
 * newer event, it would invent an older one, and the caller could not tell the difference
 * (see `docs/concepts/domain-canon.md`, version-skew contract).
 *
 * The undecoded payload itself is not copied here: it is still in the [Trace] at [index],
 * unchanged, for a build that *can* read it.
 *
 * @property index the position of the offending event in [Trace.events].
 * @property type the bus event-type discriminator ([TraceEvent.type]) — readable without
 *   decoding, so an undecodable event is still nameable.
 * @property reason the decode failure's message, for diagnostics only. Never matched on.
 */
@Serializable
data class UndecodedTraceEvent(
    val index: Int,
    val type: String,
    val reason: String,
)

/**
 * An ordered, serializable capture of a single run's `EventSerialBus` stream.
 *
 * A `Trace` is the one measurement primitive the eval set is built on: evals,
 * regression gates, and the later reward function are all consumers of a captured,
 * replayable event stream. Replay is handled by [TraceCursor].
 *
 * @property id unique identifier for this trace.
 * @property runId the run this trace was recorded for (see RECON-trace.md §3) —
 *   in v1, how the trace's [window] is identified.
 * @property arcId the orchestration pathway (Arc) this run belongs to.
 * @property createdAt epoch milliseconds when the trace was recorded.
 * @property events the captured events, in emission order.
 * @property droppedEventCount trailing events cut when the trace exceeded
 *   [TraceBudget.MAX_TRACE_BYTES] (AMPR-267's drop-with-a-marker policy).
 *   Zero for a trace that stayed within budget.
 * @property producerVersion the `AMPERE_VERSION` of the build that recorded this trace
 *   (AMPR-363), stamped by `TraceRecorder` at `RecordingHandle.stop()`. `null` for every
 *   trace recorded before the stamp existed — which is why the default is `null` rather
 *   than the current version: a stored trace must decode unchanged, and claiming it was
 *   produced by *this* build would be a lie that defeats the skew check.
 */
@Serializable
data class Trace(
    val id: String,
    val runId: String,
    val arcId: String,
    val createdAt: Long,
    val events: List<TraceEvent>,
    val droppedEventCount: Int = 0,
    val producerVersion: String? = null,
) {
    /** The replay window this trace covers: in v1, the Arc run named by [runId]. Not serialized. */
    val window: ReplayWindow get() = ReplayWindow.ArcRun(runId)

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
