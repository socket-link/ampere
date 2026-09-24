package link.socket.ampere.api.service

import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.api.model.KnowledgeProvenance

/**
 * SDK service for persistent knowledge and memory.
 *
 * Maps to CLI commands: `knowledge search`, `knowledge show`, `knowledge stats`
 *
 * ```
 * val entries = ampere.knowledge.recall("how did we handle auth?")
 * entries.forEach { println("${it.approach} — ${it.learnings}") }
 * ```
 */
@link.socket.ampere.api.AmpereStableApi
interface KnowledgeService {

    /**
     * Store a piece of knowledge with optional metadata.
     *
     * ```
     * ampere.knowledge.store(
     *     knowledge = knowledge,
     *     tags = listOf("auth", "security"),
     * )
     * ```
     *
     * @param knowledge The knowledge object to persist
     * @param tags Optional tags for categorization
     * @param taskType Optional task type for context
     * @param complexityLevel Optional complexity annotation
     * @param runId Optional Arc run correlation ID for trace projection
     */
    suspend fun store(
        knowledge: Knowledge,
        tags: List<String> = emptyList(),
        taskType: String? = null,
        complexityLevel: String? = null,
        runId: RunId? = null,
    ): Result<KnowledgeEntry>

    /**
     * Retrieve a specific knowledge entry by ID.
     *
     * ```
     * val entry = ampere.knowledge.get("knowledge-123").getOrNull()
     * entry?.let { println("${it.approach}: ${it.learnings}") }
     * ```
     *
     * @param id The ID of the knowledge entry
     * @return The knowledge entry, or null if not found
     */
    suspend fun get(id: String): Result<KnowledgeEntry?>

    /**
     * Recall knowledge relevant to a query.
     *
     * ```
     * val entries = ampere.knowledge.recall("how did we handle auth last time?")
     * entries.forEach { println("${it.approach}: ${it.learnings}") }
     * ```
     *
     * @param query Search terms to match against stored knowledge
     * @param limit Maximum number of results to return
     */
    suspend fun recall(query: String, limit: Int = 5): Result<List<KnowledgeEntry>>

    /**
     * Search knowledge with optional filters.
     *
     * When only [query] is provided, performs full-text search.
     * When type, taskType, or tags filters are provided, uses contextual search.
     *
     * ```
     * // Full-text search
     * ampere.knowledge.search(query = "authentication")
     *
     * // Filtered search
     * ampere.knowledge.search(type = KnowledgeType.FROM_OUTCOME, tags = listOf("auth"))
     * ```
     *
     * @param query Optional text query for full-text search
     * @param type Optional filter by knowledge source type
     * @param taskType Optional filter by task type
     * @param tags Optional filter by tags
     * @param limit Maximum number of results to return
     */
    suspend fun search(
        query: String? = null,
        type: KnowledgeType? = null,
        taskType: String? = null,
        tags: List<String>? = null,
        limit: Int = 10,
    ): Result<List<KnowledgeEntry>>

    /**
     * Get the tags associated with a knowledge entry.
     *
     * @param knowledgeId The ID of the knowledge entry
     * @return List of tags for the entry
     */
    suspend fun tags(knowledgeId: String): Result<List<String>>

    /**
     * Get the recorded origin of a specific knowledge entry.
     *
     * Returns the entry together with the one cognitive element it was distilled
     * from — the idea, outcome, perception, plan or task named by
     * [KnowledgeProvenance.sourceType] and [KnowledgeProvenance.sourceId].
     *
     * This is a single hop, not a trail. A knowledge row records one source id and
     * no parent entry, and the elements a source id addresses have no rows of their
     * own, so there is nothing further to follow. Entries produced by the same Arc
     * run are related through their `run_id` instead, which `ArcTraceProjection`
     * reads to rebuild that run.
     *
     * Before AMPR-350 this returned a `List<KnowledgeEntry>` documented as an
     * ordered trail. It never returned more than the one entry: it looked source ids
     * up as knowledge ids, which cannot match.
     *
     * ```
     * val origin = ampere.knowledge.provenance("knowledge-456").getOrThrow()
     * println("${origin.entry.approach} came from ${origin.sourceType}: ${origin.sourceId}")
     * ```
     *
     * @param knowledgeId The ID of the knowledge entry to trace
     * @return The entry and its source reference, or a failure if no entry has that ID
     */
    suspend fun provenance(knowledgeId: String): Result<KnowledgeProvenance>
}
