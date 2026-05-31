package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase

/**
 * Events emitted when an agent enters or exits a cognitive phase.
 *
 * These events make phase transitions directly observable on the EventSerialBus
 * without requiring consumers to poll agent state or infer phases from Spark
 * application events.
 */
@Serializable
sealed interface CognitivePhaseEvent : Event {
    val agentId: AgentId
    val nestingDepth: Int

    @Serializable
    @SerialName("PhaseEntered")
    data class PhaseEntered(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val agentId: AgentId,
        val oldPhase: CognitivePhase?,
        val newPhase: CognitivePhase,
        override val nestingDepth: Int,
        override val urgency: Urgency = Urgency.LOW,
    ) : CognitivePhaseEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Phase entered: ")
            oldPhase?.let { append("${it.name} -> ") }
            append(newPhase.name)
            append(" (depth: $nestingDepth)")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "PhaseEntered"
        }
    }

    @Serializable
    @SerialName("PhaseExited")
    data class PhaseExited(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val agentId: AgentId,
        val exitedPhase: CognitivePhase,
        val restoredToPhase: CognitivePhase?,
        override val nestingDepth: Int,
        override val urgency: Urgency = Urgency.LOW,
    ) : CognitivePhaseEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Phase exited: ${exitedPhase.name}")
            restoredToPhase?.let { append(" -> ${it.name}") }
            append(" (depth: $nestingDepth)")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "PhaseExited"
        }
    }
}
