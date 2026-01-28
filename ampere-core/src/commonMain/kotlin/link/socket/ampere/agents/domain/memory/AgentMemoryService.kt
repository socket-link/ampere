package link.socket.ampere.agents.domain.memory

import kotlinx.coroutines.withContext
import link.socket.ampere.util.ioDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID

/**
 * Service layer for persistent agent memory management.
 *
 * This service provides a clean API for agents to store and retrieve Knowledge
 * entries, handling database persistence and event emission for observability.
 *
 * It complements AgentState's in-memory knowledge tracking with long-term
 * storage and context-based retrieval capabilities. When an agent wants to
 * learn from history beyond the current session, it queries this service.
 */
class AgentMemoryService(
    private val agentId: AgentId,
    private val knowledgeRepository: KnowledgeRepository,
    private val eventBus: EventSerialBus,
) {

    /**
     * Store a knowledge entry for future recall.
     *
     * This is called when an agent extracts learnings from an experience—it
     * preserves that knowledge for retrieval in future similar contexts.
     *
     * Emits a [MemoryEvent.KnowledgeStored] event upon successful storage.
     *
     * @param knowledge The knowledge to persist
     * @param tags Optional tags for categorization and filtering
     * @param taskType Optional task type for context-based retrieval
     * @param complexityLevel Optional complexity level for similarity matching
     * @return Result containing the stored KnowledgeEntry or an error
     */
    suspend fun storeKnowledge(
        knowledge: Knowledge,
        tags: List<String> = emptyList(),
        taskType: String? = null,
        complexityLevel: ComplexityLevel? = null,
    ): Result<KnowledgeEntry> = withContext(ioDispatcher) {
        // Store knowledge using the repository
        val result = knowledgeRepository.storeKnowledge(
            knowledge = knowledge,
            tags = tags,
            taskType = taskType,
            complexityLevel = complexityLevel?.name,
        )

        // Emit event on success
        result.onSuccess { entry ->
            // Extract source ID based on knowledge type
            val sourceId = when (knowledge) {
                is Knowledge.FromIdea -> knowledge.ideaId
                is Knowledge.FromOutcome -> knowledge.outcomeId
                is Knowledge.FromPerception -> knowledge.perceptionId
                is Knowledge.FromPlan -> knowledge.planId
                is Knowledge.FromTask -> knowledge.taskId
            }

            val event = MemoryEvent.KnowledgeStored(
                eventId = generateUUID("knowledge-stored", agentId, entry.id),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Agent(agentId),
                knowledgeId = entry.id,
                knowledgeType = entry.knowledgeType,
                taskType = entry.taskType,
                tags = entry.tags,
                approach = knowledge.approach,
                learnings = knowledge.learnings,
                sourceId = sourceId,
            )

            eventBus.publish(event)
        }

        result
    }

    /**
     * Recall knowledge relevant to the current context.
     *
     * This is the core learning capability—finding past learnings that apply
     * to the current situation. The service uses a multi-strategy approach:
     * 1. Find candidates by task type, tags, full-text search, and time range
     * 2. Score each candidate against the context for relevance
     * 3. Return top-ranked results
     *
     * Emits a [MemoryEvent.KnowledgeRecalled] event with retrieval statistics.
     *
     * @param context Current situation to find relevant memories for
     * @param limit Maximum number of knowledge entries to retrieve
     * @return Result containing scored knowledge entries, ranked by relevance
     */
    suspend fun recallRelevantKnowledge(
        context: MemoryContext,
        limit: Int = 10,
    ): Result<List<KnowledgeWithScore>> = withContext(ioDispatcher) {
        runCatching {
            val candidates = mutableListOf<KnowledgeEntry>()

            // Strategy 1: Find by task type if specified
            if (context.taskType.isNotEmpty()) {
                knowledgeRepository.findKnowledgeByTaskType(
                    taskType = context.taskType,
                    limit = limit * 2, // Get more candidates for scoring
                ).onSuccess { entries ->
                    candidates.addAll(entries)
                }
            }

            // Strategy 2: Find by tags
            if (context.tags.isNotEmpty()) {
                knowledgeRepository.findKnowledgeByTags(
                    tags = context.tags.toList(),
                    limit = limit,
                ).onSuccess { entries ->
                    candidates.addAll(entries)
                }
            }

            // Strategy 3: Full-text search in approach/learnings
            if (context.description.isNotEmpty()) {
                knowledgeRepository.findSimilarKnowledge(
                    description = context.description,
                    limit = limit * 2,
                ).onSuccess { entries ->
                    candidates.addAll(entries)
                }
            }

            // Strategy 4: Temporal filtering if time constraint specified
            context.timeConstraint?.let { timeRange ->
                knowledgeRepository.findKnowledgeByTimeRange(
                    fromTimestamp = timeRange.start,
                    toTimestamp = timeRange.end,
                ).onSuccess { entries ->
                    candidates.addAll(entries)
                }
            }

            // Deduplicate and score each candidate against context
            val currentTime = Clock.System.now()
            val scoredKnowledge = candidates
                .distinctBy { it.id }
                .map { entry ->
                    // Convert KnowledgeEntry back to Knowledge for scoring
                    val knowledge = entryToKnowledge(entry)
                    KnowledgeWithScore(
                        entry = entry,
                        knowledge = knowledge,
                        relevanceScore = context.similarityScore(knowledge, currentTime),
                    )
                }
                .sortedByDescending { it.relevanceScore }
                .take(limit)

            // Emit event for observability
            val retrievedSummaries = scoredKnowledge.take(5).map { scored ->
                // Extract source ID based on knowledge type
                val sourceId = when (scored.knowledge) {
                    is Knowledge.FromIdea -> scored.knowledge.ideaId
                    is Knowledge.FromOutcome -> scored.knowledge.outcomeId
                    is Knowledge.FromPerception -> scored.knowledge.perceptionId
                    is Knowledge.FromPlan -> scored.knowledge.planId
                    is Knowledge.FromTask -> scored.knowledge.taskId
                }

                link.socket.ampere.agents.domain.event.RetrievedKnowledgeSummary(
                    knowledgeType = scored.entry.knowledgeType,
                    approach = scored.knowledge.approach,
                    learnings = scored.knowledge.learnings,
                    relevanceScore = scored.relevanceScore,
                    sourceId = sourceId,
                )
            }

            val event = MemoryEvent.KnowledgeRecalled(
                eventId = generateUUID("knowledge-recalled", agentId),
                timestamp = currentTime,
                eventSource = EventSource.Agent(agentId),
                context = context,
                resultsFound = scoredKnowledge.size,
                averageRelevance = if (scoredKnowledge.isNotEmpty()) {
                    scoredKnowledge.map { it.relevanceScore }.average()
                } else {
                    0.0
                },
                topKnowledgeIds = scoredKnowledge.map { it.entry.id },
                retrievedKnowledge = retrievedSummaries,
            )

            eventBus.publish(event)

            scoredKnowledge
        }
    }

    /**
     * Recall knowledge by its ID.
     *
     * Used when you have a specific reference to past knowledge.
     *
     * @param id The knowledge entry ID
     * @return Result containing the knowledge entry or null if not found
     */
    suspend fun recallKnowledgeById(id: String): Result<KnowledgeEntry?> = withContext(ioDispatcher) {
        knowledgeRepository.getKnowledgeById(id)
    }

    /**
     * Find knowledge entries by source type.
     *
     * Useful for querying specific types of learnings (outcomes, plans, etc.).
     *
     * @param knowledgeType The type of knowledge to retrieve
     * @param limit Maximum number of results to return
     * @return Result containing knowledge entries of the specified type
     */
    suspend fun findKnowledgeByType(
        knowledgeType: KnowledgeType,
        limit: Int = 10,
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
        knowledgeRepository.findKnowledgeByType(knowledgeType, limit)
    }

    /**
     * Find knowledge entries by task type.
     *
     * Enables querying like "show me all learnings from database migration tasks".
     *
     * @param taskType The task type to filter by
     * @param limit Maximum number of results to return
     * @return Result containing knowledge entries for this task type
     */
    suspend fun findKnowledgeByTaskType(
        taskType: String,
        limit: Int = 10,
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
        knowledgeRepository.findKnowledgeByTaskType(taskType, limit)
    }

    /**
     * Find knowledge entries by tag.
     *
     * Tags provide categorical organization for domain-specific retrieval.
     *
     * @param tag The tag to filter by
     * @param limit Maximum number of results to return
     * @return Result containing tagged knowledge entries
     */
    suspend fun findKnowledgeByTag(
        tag: String,
        limit: Int = 10,
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
        knowledgeRepository.findKnowledgeByTag(tag, limit)
    }

    /**
     * Convert a KnowledgeEntry back to a Knowledge sealed class instance.
     *
     * This is needed for similarity scoring, which operates on Knowledge objects.
     */
    private fun entryToKnowledge(entry: KnowledgeEntry): Knowledge {
        return when (entry.knowledgeType) {
            KnowledgeType.FROM_IDEA -> Knowledge.FromIdea(
                ideaId = entry.ideaId!!,
                approach = entry.approach,
                learnings = entry.learnings,
                timestamp = entry.timestamp,
            )
            KnowledgeType.FROM_OUTCOME -> Knowledge.FromOutcome(
                outcomeId = entry.outcomeId!!,
                approach = entry.approach,
                learnings = entry.learnings,
                timestamp = entry.timestamp,
            )
            KnowledgeType.FROM_PERCEPTION -> Knowledge.FromPerception(
                perceptionId = entry.perceptionId!!,
                approach = entry.approach,
                learnings = entry.learnings,
                timestamp = entry.timestamp,
            )
            KnowledgeType.FROM_PLAN -> Knowledge.FromPlan(
                planId = entry.planId!!,
                approach = entry.approach,
                learnings = entry.learnings,
                timestamp = entry.timestamp,
            )
            KnowledgeType.FROM_TASK -> Knowledge.FromTask(
                taskId = entry.taskId!!,
                approach = entry.approach,
                learnings = entry.learnings,
                timestamp = entry.timestamp,
            )
        }
    }
}

/**
 * Knowledge entry paired with its relevance score to the query context.
 *
 * Used to rank retrieved knowledge by how applicable it is to the current situation.
 * Includes both the database entry and the reconstructed Knowledge object for convenience.
 */
data class KnowledgeWithScore(
    val entry: KnowledgeEntry,
    val knowledge: Knowledge,
    val relevanceScore: Double, // 0.0 to 1.0, higher = more relevant
)
