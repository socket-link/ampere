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
}

private fun Double.formatRatio(): String {
    val rounded = (this * 1000.0).toInt() / 1000.0
    return rounded.toString()
}
