package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.type.AgentId
import link.socket.ampere.agents.domain.Urgency

typealias EventId = String
typealias EventType = String

/**
 * Base type for all events flowing through the agent system.
 *
 * This sealed hierarchy enables type-safe event definitions across KMP targets
 * and supports kotlinx.serialization for persistence and transport.
 */
@Serializable
sealed interface Event {

    /** Globally unique identifier for this event (UUID string). */
    val eventId: EventId

    /** [Instant] when the event occurred. */
    val timestamp: Instant

    /** Identifier of the agent or human that produced the event. */
    val eventSource: EventSource

    /**
     * A type discriminator for the event.
     */
    val eventType: EventType

    /** Urgency level of the event. */
    val urgency: Urgency

    /** Event emitted when a new task is created in the system. */
    @Serializable
    data class TaskCreated(
        override val eventId: EventId,
        override val urgency: Urgency,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val taskId: String,
        val description: String,
        val assignedTo: AgentId?,
    ) : Event {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "TaskCreated"
        }
    }

    /** Event emitted when an agent raises a question needing attention. */
    @Serializable
    data class QuestionRaised(
        override val eventId: EventId,
        override val urgency: Urgency,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val questionText: String,
        val context: String,
    ) : Event {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "QuestionRaised"
        }
    }

    /** Event emitted when code is submitted by an agent for review or integration. */
    @Serializable
    data class CodeSubmitted(
        override val eventId: EventId,
        override val urgency: Urgency,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val filePath: String,
        val changeDescription: String,
        val reviewRequired: Boolean,
        val assignedTo: AgentId?,
    ) : Event {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "CodeSubmitted"
        }
    }
}
