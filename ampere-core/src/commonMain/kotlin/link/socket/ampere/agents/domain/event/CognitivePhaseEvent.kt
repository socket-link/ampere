package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 *
 * A bracket a hosted run publishes also says which of the run's cycles it belongs to
 * ([cycle], AMPR-487), so a trace reader can tell a re-entered PERCEIVE from the first one
 * without deriving the cycle from the order of the brackets. Every other bracket — the Arc
 * path, `AutonomousAgent.runtimeLoop` — carries none, and its encoded form is what it was.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed interface CognitivePhaseEvent : Event {
    val agentId: AgentId
    val nestingDepth: Int

    /**
     * The hosted-run cycle this bracket belongs to, counted from 1, or null for a bracket
     * published outside a hosted run (AMPR-487). Not encoded when null, so a bracket that
     * carries none serialises byte-for-byte as it did before the field existed, and a
     * payload written before it decodes to null.
     */
    val cycle: Int?

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
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val cycle: Int? = null,
    ) : CognitivePhaseEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Phase entered: ")
            oldPhase?.let { append("${it.name} -> ") }
            append(newPhase.name)
            append(" (depth: $nestingDepth")
            cycle?.let { append(", cycle: $it") }
            append(")")
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
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        override val cycle: Int? = null,
    ) : CognitivePhaseEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Phase exited: ${exitedPhase.name}")
            restoredToPhase?.let { append(" -> ${it.name}") }
            append(" (depth: $nestingDepth")
            cycle?.let { append(", cycle: $it") }
            append(")")
            append(" ${formatUrgency(urgency)}")
            append(" from ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "PhaseExited"
        }
    }
}
