package link.socket.ampere.eval.relay

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.provider.ProviderId
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent
import link.socket.ampere.eval.trace.UndecodedTraceEvent

/**
 * One recorded model call, paired from a [Trace]'s telemetry events.
 *
 * Per RECON-relay §3.4 (AMPR-189), the model-call events are **metadata only**:
 * they carry provider, model, routing reason, token usage, latency and success —
 * but **not** the prompt or the completion text. A `RecordedModelCall` therefore
 * pins the recorded *routing decision* and its *cost metadata*; deterministic
 * replay of response *content* is out of scope for the `CognitiveRelay` seam
 * (RECON-relay Findings A & B) and is left to the `UpstreamLlmClient` seam in a
 * later ticket.
 *
 * @property started the `ProviderCallStartedEvent` for this call, or `null` when
 *   the trace recorded a completion without a matching start (RECON-relay §3.3
 *   tolerates a missing start; only [ProviderCallStartedEvent.routingReason] is
 *   then unavailable).
 * @property completed the `ProviderCallCompletedEvent` for this call (always
 *   present — pairing is keyed off completions).
 */
data class RecordedModelCall(
    val started: ProviderCallStartedEvent?,
    val completed: ProviderCallCompletedEvent,
) {
    /** Provider that served the recorded call. */
    val providerId: ProviderId get() = completed.providerId

    /** Model that served the recorded call. */
    val modelId: String get() = completed.modelId

    /** Recorded token accounting (the sole input to the Watt formula, RECON-relay §2.3). */
    val usage: TokenUsage get() = completed.usage

    /** Whether the recorded call succeeded. */
    val success: Boolean get() = completed.success

    /** The recorded routing reason, or `null` if the start event was not recorded. */
    val routingReason: String? get() = started?.routingReason
}

/**
 * The outcome of reading a [Trace]'s events: the model calls this build could reconstruct,
 * plus the events it could not decode at all.
 *
 * Both halves are needed to read the result honestly. Two recorded calls out of a trace that
 * also held three events this build cannot parse is a different fact from two calls out of a
 * trace it read completely, and a caller that only ever sees [calls] cannot tell them apart.
 *
 * @property calls the paired model calls, in completion order.
 * @property undecodedEvents every event that failed to decode, in trace order. Empty when the
 *   replaying build understood the whole trace.
 */
data class DecodedModelCalls(
    val calls: List<RecordedModelCall>,
    val undecodedEvents: List<UndecodedTraceEvent>,
)

/**
 * Decodes this trace's events, skipping (and collecting) any the running build cannot read,
 * then maps the model-call events into ordered `(request, response)` pairs (AMPR-184 task 2.1,
 * AMPR-363 task 2).
 *
 * ### Decode-or-skip
 * An event whose `"type"` discriminator this build has no serializer for — a subtype added by
 * a newer build, or a canon member from a newer minor once an `Event` carries a `CanonEntity` —
 * used to take the whole trace down with a raw `SerializationException` out of the
 * `PlaybackRelay` constructor. It is now an [UndecodedTraceEvent] in
 * [DecodedModelCalls.undecodedEvents] and the rest of the trace replays. Replay only ever needed
 * [ProviderCallStartedEvent] / [ProviderCallCompletedEvent]; every other event in a trace is
 * irrelevant to it, so skipping one costs replay nothing and skipping a model-call event shows
 * up honestly as a shorter [DecodedModelCalls.calls] plus a non-empty undecoded list.
 *
 * The tolerance is bought here, at the envelope, and nowhere else: no `defaultDeserializer` is
 * registered for `Event` or `CanonEntity` (see `docs/concepts/domain-canon.md`).
 *
 * ### Pairing
 * The pairing replicates `ArcTraceProjection.buildModelInvocations` (RECON-relay
 * §3.3) **verbatim**: each `ProviderCallCompletedEvent` is matched to the first
 * still-unconsumed `ProviderCallStartedEvent` satisfying the 6-part correlation
 * key (`timestamp <=`, `workflowId`, `agentId`, `providerId`, `modelId`,
 * `cognitivePhase`), and that start is then removed so it pairs at most once.
 * There is no correlation id — ordering is load-bearing (RECON-relay Guideline 5).
 *
 * Non-model events are ignored. Calls are enumerated in completion order, which
 * is call order for the sequential, deterministic runs evals replay.
 */
fun Trace.decodeModelCalls(json: Json = DEFAULT_JSON): DecodedModelCalls {
    val decoded = mutableListOf<Event>()
    val undecoded = mutableListOf<UndecodedTraceEvent>()

    for (traceEvent in events) {
        val event = try {
            json.decodeFromJsonElement(Event.serializer(), traceEvent.payload)
        } catch (e: SerializationException) {
            undecoded += traceEvent.toUndecoded(e)
            continue
        } catch (e: IllegalArgumentException) {
            // kotlinx throws this for a payload that is structurally wrong rather than
            // unknown (a missing discriminator, a member of the wrong JSON kind).
            undecoded += traceEvent.toUndecoded(e)
            continue
        }
        decoded += event
    }

    val starts = decoded.filterIsInstance<ProviderCallStartedEvent>().toMutableList()

    val calls = decoded.filterIsInstance<ProviderCallCompletedEvent>().map { completed ->
        val start = starts.firstOrNull { candidate ->
            candidate.timestamp <= completed.timestamp &&
                candidate.workflowId == completed.workflowId &&
                candidate.agentId == completed.agentId &&
                candidate.providerId == completed.providerId &&
                candidate.modelId == completed.modelId &&
                candidate.cognitivePhase == completed.cognitivePhase
        }
        if (start != null) starts.remove(start)
        RecordedModelCall(started = start, completed = completed)
    }

    return DecodedModelCalls(calls = calls, undecodedEvents = undecoded)
}

/**
 * The model calls this build could reconstruct from the trace, in call order.
 *
 * Shorthand for [decodeModelCalls]`().calls`. Use [decodeModelCalls] when the undecodable
 * events matter — this overload cannot distinguish "the trace held two calls" from "the trace
 * held two calls this build can read".
 */
fun Trace.modelCalls(json: Json = DEFAULT_JSON): List<RecordedModelCall> =
    decodeModelCalls(json).calls

private fun TraceEvent.toUndecoded(cause: Throwable): UndecodedTraceEvent =
    UndecodedTraceEvent(
        index = index,
        type = type,
        reason = cause.message ?: (cause::class.simpleName ?: "decode failed"),
    )
