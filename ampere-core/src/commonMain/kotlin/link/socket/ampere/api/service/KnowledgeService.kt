package link.socket.ampere.api.service

import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType

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
     */
    suspend fun store(
        knowledge: Knowledge,
        tags: List<String> = emptyList(),
        taskType: String? = null,
        complexityLevel: String? = null,
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
     * Get the provenance (origin trail) of a specific knowledge entry.
     *
     * Traces back through the chain of ideas, outcomes, and perceptions
     * that produced this knowledge.
     *
     * ```
     * val trail = ampere.knowledge.provenance("knowledge-456")
     * trail.forEach { println("${it.knowledgeType}: ${it.approach}") }
     * ```
     *
     * @param knowledgeId The ID of the knowledge entry to trace
     * @return Ordered list from most recent to original source
     */
    suspend fun provenance(knowledgeId: String): Result<List<KnowledgeEntry>>
}
