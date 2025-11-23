package link.socket.ampere.agents.events

import kotlin.reflect.KClass
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.ampere.agents.core.AgentId

typealias EventId = String
typealias EventClassType = Pair<KClass<out Event>, String>

/** Urgency levels for questions raised by agents. */
@Serializable
enum class Urgency {
    LOW,
    MEDIUM,
    HIGH,
}

/** Source of an event, either an agent or a human. */
@Serializable
sealed class EventSource {

    @Serializable
    data class Agent(val agentId: AgentId) : EventSource()

    @Serializable
    data object Human : EventSource()

    fun getIdentifier(): String = when (this) {
        is Agent -> agentId
        is Human -> "human"
    }
}

@Serializable
enum class EventStatus {
    OPEN,
    WAITING_FOR_HUMAN,
    RESOLVED,
    ;

    // ** Validation function that checks if the status transition is valid. */
    fun canTransitionTo(newStatus: EventStatus): Boolean = when (this) {
        OPEN -> when (newStatus) {
            OPEN, WAITING_FOR_HUMAN, RESOLVED -> true
        }
        WAITING_FOR_HUMAN -> when (newStatus) {
            OPEN, WAITING_FOR_HUMAN, RESOLVED -> true
        }
        RESOLVED -> newStatus == RESOLVED // RESOLVED is the terminal state
    }
}

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

    /** [kotlinx.datetime.Instant] when the event occurred. */
    val timestamp: Instant

    /** Identifier of the agent or human that produced the event. */
    val eventSource: EventSource

    /**
     * A type discriminator for the event.
     */
    val eventClassType: EventClassType

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

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object {
            private const val EVENT_TYPE = "TaskCreated"
            val EVENT_CLASS_TYPE: EventClassType = TaskCreated::class to EVENT_TYPE
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

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object {
            private const val EVENT_TYPE = "QuestionRaised"
            val EVENT_CLASS_TYPE: EventClassType = QuestionRaised::class to EVENT_TYPE
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

        @Transient
        override val eventClassType: EventClassType = EVENT_CLASS_TYPE

        companion object {
            private const val EVENT_TYPE = "CodeSubmitted"
            val EVENT_CLASS_TYPE: EventClassType = CodeSubmitted::class to EVENT_TYPE
        }
    }
}
