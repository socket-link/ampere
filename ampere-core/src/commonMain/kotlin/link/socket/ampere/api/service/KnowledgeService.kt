package link.socket.ampere.api.service

import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry

/**
 * SDK service for persistent knowledge and memory.
 *
 * Maps to CLI commands: `knowledge search`, `knowledge store`
 *
 * ```
 * val entries = ampere.knowledge.recall("how did we handle auth?")
 * entries.forEach { println("${it.approach} — ${it.learnings}") }
 * ```
 */
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
