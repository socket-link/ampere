package link.socket.ampere.eval.meter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent

/**
 * Scores a [Trace] by how closely it reproduces a [reference] trace — the regression meter a
 * golden-trace suite is built on (AMPR-187).
 *
 * ### The projection
 *
 * Two runs of the same code over the same seed produce the same *behavior*, never the same bytes:
 * every event carries a freshly generated id, and a run carries wall-clock time. Conformance is
 * therefore judged on a **projection** of each event rather than on the event itself — its
 * `type`, its [TraceEvent.truncated] flag, and its payload with every field named in
 * [volatileFields] removed, at any depth.
 *
 * Removal is by field name and recursive on purpose: `timestamp` is as volatile nested inside a
 * completion record as it is at the top of the event that carries it. The consequence to know is
 * that a field is either volatile everywhere or nowhere — pass a narrower [volatileFields] if a
 * suite needs a nested field of that name pinned.
 *
 * ### The score
 *
 * The fraction of positions whose projected events match, over the longer of the two traces — so
 * an extra or missing event costs exactly one position, and a trace of a different length can
 * never score `1.0`. A single wrong field in a one-event trace scores `0.0`; with a
 * [Tolerance] of `1.0` (the right one for a regression gate) any divergence at all is red.
 *
 * [Reading.detail] names the first divergent position and what each side had there, which is
 * usually the whole diagnosis.
 *
 * @param reference the golden trace this meter measures against.
 * @param volatileFields payload field names excluded from the comparison at every depth; defaults
 *   to [DEFAULT_VOLATILE_FIELDS].
 */
class TraceConformanceMeter(
    val meterId: String,
    private val reference: Trace,
    private val tolerance: Tolerance,
    private val volatileFields: Set<String> = DEFAULT_VOLATILE_FIELDS,
) : Meter {

    override suspend fun measure(trace: Trace): Result<Reading> {
        if (reference.events.isEmpty()) {
            return Result.failure(MeterError.EmptyReferenceTrace(meterId, reference.id))
        }
        if (trace.events.isEmpty()) {
            return Result.failure(MeterError.EmptyTrace(meterId))
        }

        val expected = reference.events.map(::project)
        val actual = trace.events.map(::project)

        val compared = maxOf(expected.size, actual.size)
        val matches = expected.zip(actual).count { (e, a) -> e == a }
        val score = matches.toDouble() / compared

        val firstDivergence = (0 until compared).firstOrNull { index ->
            expected.getOrNull(index) != actual.getOrNull(index)
        }

        val detail = if (firstDivergence == null) {
            emptyMap()
        } else {
            buildMap {
                put("first_divergence_index", firstDivergence.toString())
                put("expected_event_count", expected.size.toString())
                put("actual_event_count", actual.size.toString())
                put("expected", expected.getOrNull(firstDivergence).describe())
                put("actual", actual.getOrNull(firstDivergence).describe())
            }
        }

        return Result.success(
            Reading(score = score, passed = tolerance.passes(score), meterId = meterId, detail = detail),
        )
    }

    /** One event reduced to what conformance is judged on. */
    private data class ProjectedEvent(
        val type: String,
        val truncated: Boolean,
        val payload: JsonElement,
    )

    private fun project(event: TraceEvent): ProjectedEvent = ProjectedEvent(
        type = event.type,
        truncated = event.truncated,
        payload = event.payload.withoutVolatileFields(),
    )

    private fun JsonElement.withoutVolatileFields(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            filterKeys { it !in volatileFields }
                .mapValues { (_, value) -> value.withoutVolatileFields() },
        )

        is JsonArray -> JsonArray(map { it.withoutVolatileFields() })
        else -> this
    }

    private fun ProjectedEvent?.describe(): String =
        this?.let { "${it.type} ${it.payload}" } ?: "(absent)"

    companion object {
        /**
         * Field names excluded from conformance by default: the identifiers and the timing.
         *
         * `eventId`, `runId` and `arcRunId` are generated per run; `timestamp`, `recordedAt`,
         * `createdAt` and the execution stamps are wall-clock. None of them is behavior, and all
         * of them differ between two runs of identical code.
         */
        val DEFAULT_VOLATILE_FIELDS: Set<String> = setOf(
            "eventId",
            "runId",
            "arcRunId",
            "timestamp",
            "recordedAt",
            "createdAt",
            "executionStartTimestamp",
            "executionEndTimestamp",
        )
    }
}
