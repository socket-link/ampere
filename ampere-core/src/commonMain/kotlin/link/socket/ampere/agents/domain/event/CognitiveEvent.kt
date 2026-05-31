package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase

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
}

private fun Double.formatRatio(): String {
    val rounded = (this * 1000.0).toInt() / 1000.0
    return rounded.toString()
}
