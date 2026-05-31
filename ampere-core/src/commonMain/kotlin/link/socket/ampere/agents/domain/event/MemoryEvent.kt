package link.socket.ampere.agents.domain.event

import kotlin.math.roundToInt
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.task.TaskId

/** Format a double to 2 decimal places (multiplatform compatible). */
private fun Double.formatPercent(): String {
    val scaled = (this * 100).roundToInt() / 100.0
    val intPart = scaled.toInt()
    val decPart = ((scaled - intPart) * 100).roundToInt()
    return "$intPart.${decPart.toString().padStart(2, '0')}"
}

/**
 * Summary of a retrieved knowledge entry for logging purposes.
 */
@Serializable
data class RetrievedKnowledgeSummary(
    val knowledgeType: KnowledgeType,
    val approach: String,
    val learnings: String,
    val relevanceScore: Double,
    val sourceId: String?,
)

/**
 * Base sealed interface for memory-related events.
 *
 * Memory events track the lifecycle of Knowledge storage and retrieval,
 * providing observability into the agent learning process.
 */
sealed interface MemoryEvent : Event {

    // TODO(AMPR-176): Add `ProvenanceCommitted` here when the provenance-chain
    // surface is promoted from design placeholder to implementation. Design
    // sketch in docs/design/provenance-event.md covers event shape
    // (entryId / parentHash / contentHash / signer), hash/signature scheme,
    // publish-site choice, and the relationship to the OpenAI grant's
    // formal-verification workstream. Do not implement here without first
    // satisfying one of the promotion criteria in that doc.

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
        val approach: String? = null, // What approach was tried
        val learnings: String? = null, // What was learned
        val sourceId: String? = null, // The outcome/idea/task ID this came from
        val runId: String? = null,
    ) : MemoryEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Knowledge stored from ")
            append(knowledgeType.toString().replace("FROM_", "").lowercase().replaceFirstChar { it.uppercase() })

            // Show source ID if available
            sourceId?.let { append(" [$it]") }

            // Show what was learned (truncated)
            approach?.let {
                val truncated = if (it.length > 60) it.take(60) + "..." else it
                append(": \"$truncated\"")
            }

            // Show task type and tags as context
            val contextParts = mutableListOf<String>()
            taskType?.let { contextParts.add("task=$it") }
            if (tags.isNotEmpty()) {
                contextParts.add("tags=${tags.take(2).joinToString(",")}")
            }
            if (contextParts.isNotEmpty()) {
                append(" (${contextParts.joinToString(", ")})")
            }

            append(" by ${formatSource(eventSource)}")
        }

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
        val retrievedKnowledge: List<RetrievedKnowledgeSummary> = emptyList(), // Summaries of what was retrieved
        val runId: String? = null,
    ) : MemoryEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            // Header: how many results
            append("Knowledge recalled: $resultsFound result(s)")
            if (resultsFound > 0) {
                val roundedRelevance = ((averageRelevance * 100).toInt()) / 100.0
                append(" (avg relevance: ${roundedRelevance.formatPercent()})")
            }

            // Query context
            if (context.description.isNotEmpty()) {
                val truncated = if (context.description.length > 60) {
                    context.description.take(60) + "..."
                } else {
                    context.description
                }
                append(" for: \"$truncated\"")
            }

            // Show task type and tags if specified
            val contextParts = mutableListOf<String>()
            if (context.taskType.isNotEmpty()) {
                contextParts.add("task=${context.taskType}")
            }
            if (context.tags.isNotEmpty()) {
                contextParts.add("tags=${context.tags.take(2).joinToString(",")}")
            }
            if (contextParts.isNotEmpty()) {
                append(" (${contextParts.joinToString(", ")})")
            }

            append(" by ${formatSource(eventSource)}")

            // Show what was actually retrieved (up to 3 entries)
            if (retrievedKnowledge.isNotEmpty()) {
                append("\n  Retrieved:")
                retrievedKnowledge.take(3).forEachIndexed { index, summary ->
                    append("\n    ${index + 1}. [${summary.relevanceScore.formatPercent()}] ")
                    val approachSnippet = if (summary.approach.length > 80) {
                        summary.approach.take(80) + "..."
                    } else {
                        summary.approach
                    }
                    append("\"$approachSnippet\"")
                }
                if (retrievedKnowledge.size > 3) {
                    append("\n    ... and ${retrievedKnowledge.size - 3} more")
                }
            }
        }

        companion object {
            const val EVENT_TYPE: EventType = "KnowledgeRecalled"
        }
    }

    /**
     * Event emitted when an agent reaches a meaningful checkpoint.
     *
     * Milestones are low-volume signals for significant agent progress. They are
     * intentionally separate from [KnowledgeStored] so consumers can subscribe to
     * milestone semantics without filtering routine memory writes.
     */
    @Serializable
    data class MilestoneReached(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        val agentId: AgentId,
        val milestoneId: String,
        val description: String,
        val knowledgeId: String?,
        val taskId: TaskId?,
        val runId: String?,
        val category: MilestoneCategory,
        override val urgency: Urgency = Urgency.MEDIUM,
    ) : MemoryEvent {

        override val eventType: EventType = EVENT_TYPE

        override fun getSummary(
            formatUrgency: (Urgency) -> String,
            formatSource: (EventSource) -> String,
        ): String = buildString {
            append("Milestone reached: ")
            append(category.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
            append(" - ")
            append(description)
            taskId?.let { append(" (task=$it)") }
            knowledgeId?.let { append(" knowledge=$it") }
            append(" ${formatUrgency(urgency)}")
            append(" by ${formatSource(eventSource)}")
        }

        companion object {
            const val EVENT_TYPE: EventType = "MilestoneReached"
        }
    }
}

/**
 * Categories for [MemoryEvent.MilestoneReached].
 *
 * [FIRST_SUCCESS] and [RECOVERY] are emitted by the built-in milestone tracker.
 * [EXTERNAL] is emitted through the explicit milestone API for human-in-the-loop
 * approvals or external orchestration scripts. [KEY_INSIGHT] and [CHECKPOINT]
 * are available for future implementation when AMPERE promotes insight and
 * long-running progress signals into explicit milestone publish sites.
 */
@Serializable
enum class MilestoneCategory {
    FIRST_SUCCESS,
    KEY_INSIGHT,
    RECOVERY,
    CHECKPOINT,
    EXTERNAL,
}
