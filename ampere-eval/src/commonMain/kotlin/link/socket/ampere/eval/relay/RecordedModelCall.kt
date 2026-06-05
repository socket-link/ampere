package link.socket.ampere.eval.relay

import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.provider.ProviderId
import link.socket.ampere.eval.trace.Trace

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
 * Maps this trace's ordered model-call events into an ordered list of
 * `(request, response)` pairs (AMPR-184 task 2.1).
 *
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
fun Trace.modelCalls(json: Json = DEFAULT_JSON): List<RecordedModelCall> {
    val decoded = events.map { json.decodeFromJsonElement(Event.serializer(), it.payload) }
    val starts = decoded.filterIsInstance<ProviderCallStartedEvent>().toMutableList()

    return decoded.filterIsInstance<ProviderCallCompletedEvent>().map { completed ->
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
}
