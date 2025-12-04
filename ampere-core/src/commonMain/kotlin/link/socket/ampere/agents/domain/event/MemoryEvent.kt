package link.socket.ampere.agents.domain.event

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.concept.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.memory.MemoryContext

/**
 * Base sealed interface for memory-related events.
 *
 * Memory events track the lifecycle of Knowledge storage and retrieval,
 * providing observability into the agent learning process.
 */
sealed interface MemoryEvent : Event {

    /**
     * Event emitted when a Knowledge entry is successfully stored.
     *
     * This event signals that an agent has extracted learnings from
     * a cognitive element (Idea, Outcome, Perception, Plan, or Task)
     * and persisted it for future recall.
     */
    @Serializable
    data class KnowledgeStored(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val knowledgeId: String,
        val knowledgeType: KnowledgeType,
        val taskType: String?,
        val tags: List<String>,
        override val urgency: Urgency = Urgency.LOW,
    ) : MemoryEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "KnowledgeStored"
        }
    }

    /**
     * Event emitted when Knowledge entries are recalled based on context.
     *
     * This event provides observability into the agent's learning process—
     * showing when past learnings are being consulted and how relevant
     * they were to the current situation.
     */
    @Serializable
    data class KnowledgeRecalled(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val context: MemoryContext,
        val resultsFound: Int,
        val averageRelevance: Double,
        val topKnowledgeIds: List<String>,
        override val urgency: Urgency = Urgency.LOW,
    ) : MemoryEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "KnowledgeRecalled"
        }
    }
}
