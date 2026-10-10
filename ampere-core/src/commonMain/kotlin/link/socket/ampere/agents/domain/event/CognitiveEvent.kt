package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.reasoning.ConfidenceSource
import link.socket.ampere.agents.domain.routing.local.InferenceLocality
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.llm.decide.ModelSnapshot

/**
 * Events emitted by cognitive evaluators outside normal phase transitions.
 */
@Serializable
sealed interface CognitiveEvent : Event {

    val agentId: AgentId

    /**
     * Emitted when an agent's uncertainty meets or exceeds its configured escalation threshold.
     */
    @Serializable
    @SerialName("CognitiveEvent.EscalationFired")
    data class EscalationFired(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.HIGH,
        override val agentId: AgentId,
        val uncertaintyValue: Double,
        val threshold: Double,
        val prompt: String,
        val cognitivePhase: CognitivePhase?,
    ) : CognitiveEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Escalation fired for $agentId: uncertainty ")
            append(uncertaintyValue.formatRatio())
            append(" >= threshold ")
            append(threshold.formatRatio())
            cognitivePhase?.let { append(" [${it.name}]") }
            if (prompt.isNotBlank()) {
                append(" - ${prompt.take(80)}")
                if (prompt.length > 80) append("...")
            }
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "EscalationFired"
        }
    }

    /**
     * Emitted on every uncertainty evaluation, including near-misses that do not trip the threshold.
     *
     * Use this event for telemetry — uncertainty trajectory, calibration analysis, "about to fire"
     * warnings — not for action signals. When an evaluation also fires, an [EscalationFired] is
     * published immediately after this event (causal order at the publish site; subscribers cannot
     * assume cross-event handler ordering because bus dispatch is concurrent).
     *
     * Volume warning: uncertainty may be evaluated on every LLM call or tool invocation, producing
     * thousands of events per agent run. Subscribe only if you have a real use for the data;
     * non-telemetry consumers should filter this event out. Urgency is [Urgency.LOW] for the same
     * reason.
     */
    @Serializable
    @SerialName("CognitiveEvent.EscalationConsidered")
    data class EscalationConsidered(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.LOW,
        override val agentId: AgentId,
        val uncertaintyValue: Double,
        val threshold: Double,
        /** True iff this evaluation also produced an [EscalationFired]. */
        val fired: Boolean,
        val cognitivePhase: CognitivePhase?,
    ) : CognitiveEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Escalation considered for $agentId: uncertainty ")
            append(uncertaintyValue.formatRatio())
            append(if (fired) " >= threshold " else " < threshold ")
            append(threshold.formatRatio())
            cognitivePhase?.let { append(" [${it.name}]") }
            append(if (fired) " (fired)" else " (near-miss)")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "EscalationConsidered"
        }
    }

    /**
     * The record of one judgment a decision call produced (AMPR-384, J4).
     *
     * `AgentReasoning.decide` publishes one of these per judgment in a call's response, through
     * the agent's event door, so that a band can later be fitted and a model compared with its
     * replacement. It records what the call was and what came back — never the state: a state
     * may hold a person's data, and [stateDigest] is enough to join on.
     *
     * [distribution] is present only when the adapter measured one (a System One provider or
     * the deterministic adapter); [source] says which. [band] is the band applied to read the
     * judgment, and is null throughout W1, which fits none. [causedBy] is the Action or write
     * the judgment bears on; an [EscalationConsidered] later derived from this judgment (J8)
     * is linked back to it by the same `caused_by` and is otherwise untouched.
     *
     * Volume warning: this fires on every judgment. [Urgency.LOW] for the same reason as
     * [EscalationConsidered].
     */
    @Serializable
    @SerialName("CognitiveEvent.JudgmentRecorded")
    data class JudgmentRecorded(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency = Urgency.LOW,
        override val agentId: AgentId,
        /** Groups the records of one call; a call with one question leaves one record. */
        val callId: String,
        /** The question's id in the request. */
        val questionId: String,
        /** The digest of the question as asked; a reworded question is a new version. */
        val questionVersion: String,
        /** `noul`, `choice` or `score`. */
        val questionType: String,
        /** SHA-256 of the state. The state itself is never recorded. */
        val stateDigest: String,
        /** One of the question's declared answer keys. */
        val answer: String,
        /** Probability per declared answer, when the adapter measured one. */
        val distribution: Map<String, Double>? = null,
        /** `p(answer)` — measured, or the F10 mapping of a self-report; see [source]. */
        val confidence: Double? = null,
        val source: ConfidenceSource,
        /** The band the judgment was read against. Always null in W1. */
        val band: String? = null,
        val modelSnapshot: ModelSnapshot,
        val locality: InferenceLocality,
        /** Client-observed, for the whole call. */
        val latencyMs: Long,
        /** As the transport reported it, for the whole call. */
        val usage: TokenUsage = TokenUsage(),
        /** The Action or write this judgment bears on, if any. */
        val causedBy: String? = null,
        val cognitivePhase: CognitivePhase? = null,
    ) : CognitiveEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Judgment recorded for $agentId: $questionId ($questionType) = $answer")
            confidence?.let { append(" p=${it.formatRatio()}") }
            append(" [${source.name.lowercase()}")
            if (distribution == null) append(", no distribution")
            append("]")
            cognitivePhase?.let { append(" [${it.name}]") }
            append(" via ${modelSnapshot.providerId}/${modelSnapshot.modelId}")
            append(" ${locality.name.lowercase()}")
            append(" ${latencyMs}ms")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "JudgmentRecorded"
        }
    }
}

private fun Double.formatRatio(): String {
    val rounded = (this * 1000.0).toInt() / 1000.0
    return rounded.toString()
}
