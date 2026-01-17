package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency

/**
 * Events related to cognitive state transitions in the Spark system.
 *
 * These events make internal cognitive evolution observable alongside external
 * actions. They complement existing event types (MessagePosted, TicketAssigned, etc.)
 * by showing when and how an agent's cognitive focus changes.
 *
 * All SparkEvents share common properties:
 * - agentId: Which agent's cognition changed
 * - stackDepth: How many Sparks are now on the stack
 * - stackDescription: Human-readable stack state
 *
 * Note: This is not a sealed interface as the implementations are concrete data classes
 * in this same file.
 */
interface SparkEvent : Event {
    /** The ID of the agent whose cognitive state changed. */
    val agentId: AgentId

    /** The number of Sparks currently on the stack after this event. */
    val stackDepth: Int

    /** Human-readable description of the current stack state. */
    val stackDescription: String
}

/**
 * Event emitted when a Spark is pushed onto the cognitive context stack.
 *
 * This indicates the agent is specializing its focus by adding a new layer
 * of context. The sparkType and sparkName identify what kind of specialization
 * occurred.
 */
@Serializable
@SerialName("SparkAppliedEvent")
data class SparkAppliedEvent(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    override val urgency: Urgency = Urgency.LOW,
    override val agentId: AgentId,
    override val stackDepth: Int,
    override val stackDescription: String,
    /** Human-readable name of the Spark (e.g., "Project:ampere", "Role:Code"). */
    val sparkName: String,
    /** Simple class name of the Spark type (e.g., "ProjectSpark", "RoleSpark.Code"). */
    val sparkType: String,
) : SparkEvent {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        append("⚡ Spark applied: $sparkName")
        append(" (depth: $stackDepth)")
        append(" ${formatUrgency(urgency)}")
        append(" from ${formatSource(eventSource)}")
    }

    companion object {
        const val EVENT_TYPE: EventType = "SparkApplied"
    }
}

/**
 * Event emitted when a Spark is popped from the cognitive context stack.
 *
 * This indicates the agent is returning to a broader context by removing
 * the most recent specialization layer.
 */
@Serializable
@SerialName("SparkRemovedEvent")
data class SparkRemovedEvent(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    override val urgency: Urgency = Urgency.LOW,
    override val agentId: AgentId,
    override val stackDepth: Int,
    override val stackDescription: String,
    /** Name of the Spark that was removed. */
    val previousSparkName: String,
) : SparkEvent {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        append("💨 Spark removed: $previousSparkName")
        append(" (depth: $stackDepth)")
        append(" ${formatUrgency(urgency)}")
        append(" from ${formatSource(eventSource)}")
    }

    companion object {
        const val EVENT_TYPE: EventType = "SparkRemoved"
    }
}

/**
 * Event capturing a complete snapshot of cognitive state.
 *
 * This is a richer event that captures the full cognitive state at a point
 * in time. It can be emitted periodically, on-demand, or at significant
 * lifecycle points for debugging and observability.
 */
@Serializable
@SerialName("CognitiveStateSnapshot")
data class CognitiveStateSnapshot(
    override val eventId: EventId,
    override val timestamp: Instant,
    override val eventSource: EventSource,
    override val urgency: Urgency = Urgency.LOW,
    override val agentId: AgentId,
    override val stackDepth: Int,
    override val stackDescription: String,
    /** The cognitive affinity name. */
    val affinity: String,
    /** Names of all Sparks on the stack, in order. */
    val sparkNames: List<String>,
    /** Length of the effective system prompt. */
    val effectivePromptLength: Int,
    /** Number of available tools after all constraints, or null if unlimited. */
    val availableToolCount: Int?,
) : SparkEvent {

    override val eventType: EventType = EVENT_TYPE

    override fun getSummary(
        formatUrgency: (Urgency) -> String,
        formatSource: (EventSource) -> String,
    ): String = buildString {
        append("🧠 Cognitive snapshot: [$affinity]")
        if (sparkNames.isNotEmpty()) {
            append(" + ${sparkNames.size} sparks")
        }
        append(" (prompt: $effectivePromptLength chars)")
        append(" ${formatUrgency(urgency)}")
        append(" from ${formatSource(eventSource)}")
    }

    companion object {
        const val EVENT_TYPE: EventType = "CognitiveStateSnapshot"
    }
}
