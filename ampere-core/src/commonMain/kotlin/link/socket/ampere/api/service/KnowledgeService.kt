package link.socket.ampere.api.service

import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.knowledge.BeliefConfidence
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.knowledge.reportedConfidence
import link.socket.ampere.api.model.KnowledgeProvenance
import link.socket.ampere.field.Sensitivity

/**
 * SDK service for persistent knowledge and memory.
 *
 * Maps to CLI commands: `knowledge search`, `knowledge show`, `knowledge stats`
 *
 * ```
 * val entries = ampere.knowledge.recall("how did we handle auth?")
 * entries.forEach { println("${it.approach} — ${it.learnings}") }
 * ```
 *
 * The service writes and reads as one Subject — the identity of the door it publishes through
 * (F9, F12). What it stores is held by that Subject; what it reads is every PUBLIC entry plus
 * the ones that Subject holds, never another Subject's PRIVATE or PROTECTED entry.
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
     * @param confidence How confident the holder is (F10); defaults to what [knowledge]
     *   reported about itself. A measured probability is stored as given, a self-reported
     *   level as its F10 mapping
     * @param sensitivity Who may read the entry (F12). A belief about a real person is
     *   [Sensitivity.PROTECTED]: no other Subject reads it, no replay or bake contains it, and
     *   its `KnowledgeStored` event carries none of its content
     * @param causedBy The event this write is reacting to, if any — the next step of the
     *   entry's [provenance] after its own `KnowledgeStored`
     */
    suspend fun store(
        knowledge: Knowledge,
        tags: List<String> = emptyList(),
        taskType: String? = null,
        complexityLevel: String? = null,
        runId: RunId? = null,
        confidence: BeliefConfidence? = knowledge.reportedConfidence,
        sensitivity: Sensitivity = Sensitivity.PUBLIC,
        causedBy: EventId? = null,
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
     * Returns the entry, the one cognitive element it was distilled from
     * ([KnowledgeProvenance.sourceType] and [KnowledgeProvenance.sourceId]), and the events
     * that produced it ([KnowledgeProvenance.events]): the `KnowledgeStored` its row names in
     * `created_by_event`, then that event's `caused_by`, and so on to an event with no cause.
     *
     * The walk is over events, never over knowledge rows (F11, AMPR-417). Before AMPR-350 this
     * walked source ids as if they were knowledge ids, which cannot match; until AMPR-417 it
     * returned the single source hop and nothing else, because no row recorded an event.
     *
     * ```
     * val origin = ampere.knowledge.provenance("knowledge-456").getOrThrow()
     * origin.events.forEach { println("${it.event.eventType} ${it.event.eventId}") }
     * ```
     *
     * @param knowledgeId The ID of the knowledge entry to trace
     * @return The entry, its source reference and its event chain, or a failure if no entry
     *   this service may read has that ID
     */
    suspend fun provenance(knowledgeId: String): Result<KnowledgeProvenance>
}
