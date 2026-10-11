package link.socket.ampere.agents.domain.memory

import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.RetrievedKnowledgeSummary
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.util.ioDispatcher

/**
 * Service layer for persistent agent memory management.
 *
 * This service provides a clean API for agents to store and retrieve Knowledge
 * entries, handling database persistence and event emission for observability.
 *
 * It complements AgentState's in-memory knowledge tracking with long-term
 * storage and context-based retrieval capabilities. When an agent wants to
 * learn from history beyond the current session, it queries this service.
 *
 * Every event this service emits goes through [eventApi] (F1, AMPR-339): the door persists
 * it to the `EventStore` before dispatching it on the bus, so `KnowledgeStored` is a durable
 * record whose `eventSource` names the holding agent (the F9/F11 provenance anchor).
 *
 * @param agentId the agent whose memory this is; also the door's identity, so the two must
 *   agree — factories build the door with `createEventApi(agentId)`.
 * @param eventApi the one door every event leaves through.
 */
class AgentMemoryService(
    private val agentId: AgentId,
    private val knowledgeRepository: KnowledgeRepository,
    private val eventApi: AgentEventApi,
) {

    /**
     * Store a knowledge entry for future recall.
     *
     * This is called when an agent extracts learnings from an experience—it
     * preserves that knowledge for retrieval in future similar contexts.
     *
     * Emits a [MemoryEvent.KnowledgeStored] event upon successful storage. The event is
     * persisted through the door before this returns; a persist failure fails the result,
     * so a caller never sees a stored entry whose provenance record is missing.
     *
     * @param knowledge The knowledge to persist
     * @param tags Optional tags for categorization and filtering
     * @param taskType Optional task type for context-based retrieval
     * @param complexityLevel Optional complexity level for similarity matching
     * @param runId Optional Arc run correlation ID for trace projection; also stamped on
     *   the event's envelope (F4)
     * @return Result containing the stored KnowledgeEntry or an error
     */
    suspend fun storeKnowledge(
        knowledge: Knowledge,
        tags: List<String> = emptyList(),
        taskType: String? = null,
        complexityLevel: ComplexityLevel? = null,
        runId: RunId? = null,
    ): Result<KnowledgeEntry> = withContext(ioDispatcher) {
        // Store knowledge using the repository
        val result = knowledgeRepository.storeKnowledge(
            knowledge = knowledge,
            tags = tags,
            taskType = taskType,
            complexityLevel = complexityLevel?.name,
            runId = runId,
        )

        // Emit event on success; a door failure folds into the result
        result.mapCatching { entry ->
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
                sourceId = knowledge.sourceId,
                runId = runId,
            )

            eventApi.publish(event, runId = runId).getOrThrow()
            entry
        }
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
     * @param runId Arc run this recall belongs to; stamped on the event *and* on its envelope
     *   (F4, AMPR-386), so a run's Recall phase is on its own trace rather than reachable only
     *   through the projection's payload fallback. Null when the caller holds no run.
     * @return Result containing scored knowledge entries, ranked by relevance
     */
    suspend fun recallRelevantKnowledge(
        context: MemoryContext,
        limit: Int = 10,
        runId: RunId? = null,
    ): Result<List<KnowledgeWithScore>> = withContext(ioDispatcher) {
        runCatching {
            val currentTime = Clock.System.now()
            val scoredKnowledge = scoreCandidates(context, limit, currentTime)

            val event = MemoryEvent.KnowledgeRecalled(
                eventId = generateUUID("knowledge-recalled", agentId),
                timestamp = currentTime,
                eventSource = EventSource.Agent(agentId),
                context = context,
                resultsFound = scoredKnowledge.size,
                averageRelevance = scoredKnowledge.averageRelevance(),
                topKnowledgeIds = scoredKnowledge.map { it.entry.id },
                retrievedKnowledge = scoredKnowledge.retrievedSummaries(),
                runId = runId,
            )

            eventApi.publish(event, runId = runId).getOrThrow()

            scoredKnowledge
        }
    }

    /**
     * [recallRelevantKnowledge] without the event: the same query, the same scoring,
     * nothing published (AMPR-394).
     *
     * For the one caller that publishes a `KnowledgeRecalled` of its own because its
     * RECALL found more than this: a hosted run merges what this returns with what the
     * consumer's [RecallSource][link.socket.ampere.memory.RecallSource]s returned and
     * publishes *one* event summarising both. Two events — one here for the memory half
     * and one there for the whole — would double-count a single phase on the trace.
     *
     * `internal`, and deliberately not the easy call: an in-loop recall that leaves no
     * `KnowledgeRecalled` behind is a recall the trace cannot show, so the only caller
     * that may skip it is one that publishes the event itself.
     */
    internal suspend fun scoreRecall(
        context: MemoryContext,
        limit: Int = 10,
    ): Result<List<KnowledgeWithScore>> = withContext(ioDispatcher) {
        runCatching { scoreCandidates(context, limit, Clock.System.now()) }
    }

    /**
     * The four candidate strategies, deduplicated, scored against [context] and cut to
     * [limit].
     *
     * Each strategy's failure is dropped rather than failing the recall: a repository
     * whose full-text index rejects the query still leaves the task-type and tag hits
     * worth scoring. Which is also why a recall that returns nothing is not evidence
     * that nothing is stored.
     */
    private suspend fun scoreCandidates(
        context: MemoryContext,
        limit: Int,
        currentTime: Instant,
    ): List<KnowledgeWithScore> {
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
        return candidates
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
 * The id of the cognitive element [this] was distilled from — exactly one of five,
 * decided by which source the entry records.
 *
 * A knowledge entry records one source and no lineage, so this is the whole of its
 * provenance within its run. It is a *source* id, not a knowledge id: nothing resolves
 * it back to a row, and `getKnowledgeById` on it can never match.
 */
internal val Knowledge.sourceId: String
    get() = when (this) {
        is Knowledge.FromIdea -> ideaId
        is Knowledge.FromOutcome -> outcomeId
        is Knowledge.FromPerception -> perceptionId
        is Knowledge.FromPlan -> planId
        is Knowledge.FromTask -> taskId
    }

/**
 * The top few entries as [MemoryEvent.KnowledgeRecalled] summarises what it retrieved.
 *
 * Capped rather than complete: the event is a record of the recall, not a copy of the
 * store, and a recall of ten entries would otherwise put ten whole learnings on the
 * bus and in the `EventStore`.
 */
internal fun List<KnowledgeWithScore>.retrievedSummaries(
    cap: Int = RECALL_SUMMARY_CAP,
): List<RetrievedKnowledgeSummary> = take(cap).map { scored ->
    RetrievedKnowledgeSummary(
        knowledgeType = scored.entry.knowledgeType,
        approach = scored.knowledge.approach,
        learnings = scored.knowledge.learnings,
        relevanceScore = scored.relevanceScore,
        sourceId = scored.knowledge.sourceId,
    )
}

/** The mean relevance of a scored recall; `0.0` when it found nothing. */
internal fun List<KnowledgeWithScore>.averageRelevance(): Double =
    if (isEmpty()) 0.0 else map { it.relevanceScore }.average()

/** How many retrieved entries a `KnowledgeRecalled` carries summaries for. */
internal const val RECALL_SUMMARY_CAP: Int = 5

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

/**
 * Floor on [KnowledgeWithScore.relevanceScore] used by the insight extractors
 * when the caller does not choose one.
 *
 * How relevant is relevant enough is caller policy, not a fact about a recalled
 * entry: an agent summarising a hundred entries wants a high floor, one working
 * from three wants none. The constant exists only so a caller that has no
 * opinion still gets a defined answer, and it is named so that raising it is a
 * one-line change rather than a hunt for repeated literals.
 *
 * `0.5` is the midpoint of the `0.0..1.0` range [AgentMemoryService] scores
 * into, and is a representative default rather than a calibrated one — nothing
 * has measured which floor produces better plans.
 *
 * Compared strictly: an entry scoring exactly the floor is excluded.
 */
const val DEFAULT_RELEVANCE_FLOOR: Double = 0.5
