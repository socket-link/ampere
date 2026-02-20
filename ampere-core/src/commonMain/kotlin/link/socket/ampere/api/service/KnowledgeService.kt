package link.socket.ampere.api.service

import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry

/**
 * SDK service for persistent knowledge and memory.
 *
 * Maps to CLI command: `knowledge search`
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
     * ampere.knowledge.recall("how did we handle auth last time?")
     * ```
     */
    suspend fun recall(query: String, limit: Int = 5): Result<List<KnowledgeEntry>>
}
