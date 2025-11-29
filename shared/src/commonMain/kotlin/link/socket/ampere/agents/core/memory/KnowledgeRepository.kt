package link.socket.ampere.agents.core.memory

import kotlinx.datetime.Instant

/**
 * Repository for storing and retrieving Knowledge entries to enable agent learning.
 *
 * This is the recall layer over the existing AgentMemoryCell.Past structures—
 * providing semantic search over accumulated learnings. When an agent faces a
 * task, it can query "show me all past attempts at database migrations" or
 * "what did I learn about test-first development?"
 *
 * The repository enables contextual retrieval based on:
 * - Semantic similarity to current situation
 * - Source type (FromIdea, FromOutcome, etc.)
 * - Task types and complexity levels
 * - Tags for categorical organization
 * - Temporal ranges for recent vs historical learnings
 */
interface KnowledgeRepository {

    /**
     * Store a knowledge entry extracted from cognitive work.
     *
     * This should be called whenever an agent extracts learnings from
     * any cognitive element (Idea, Outcome, Perception, Plan, Task).
     * Both successes and failures are valuable—failures teach what
     * doesn't work, which is as important as knowing what does.
     *
     * @param knowledge The knowledge entry to store
     * @param tags Optional tags for categorization (e.g., "testing", "database", "validation")
     * @param taskType Optional task type for contextual filtering
     * @param complexityLevel Optional complexity indicator
     * @return Result with the stored KnowledgeEntry or an error
     */
    suspend fun storeKnowledge(
        knowledge: Knowledge,
        tags: List<String> = emptyList(),
        taskType: String? = null,
        complexityLevel: String? = null,
    ): Result<KnowledgeEntry>

    /**
     * Find knowledge entries semantically similar to the given description.
     *
     * This is the core learning query: "Before I try this, let me see if
     * anyone's done something similar and what happened."
     *
     * The similarity matching is deliberately fuzzy—we want to find
     * analogous situations even if they're not exact matches. A past
     * learning about "add input validation to UserRepository" might inform
     * a current task of "add validation to OrderRepository".
     *
     * @param description The context or search terms to match against
     * @param limit Maximum number of results to return
     * @return Result with list of similar knowledge entries, most recent first
     */
    suspend fun findSimilarKnowledge(
        description: String,
        limit: Int = 5,
    ): Result<List<KnowledgeEntry>>

    /**
     * Find knowledge entries by source type.
     *
     * Useful for querying specific types of learnings:
     * - FROM_OUTCOME: What happened when we executed approaches
     * - FROM_PLAN: What we learned during planning phases
     * - FROM_PERCEPTION: Insights from environmental observations
     * - FROM_IDEA: Learnings from ideation processes
     * - FROM_TASK: Knowledge extracted from task execution
     *
     * @param knowledgeType The type of knowledge to retrieve
     * @param limit Maximum number of results to return
     * @return Result with knowledge entries of the specified type
     */
    suspend fun findKnowledgeByType(
        knowledgeType: KnowledgeType,
        limit: Int = 10,
    ): Result<List<KnowledgeEntry>>

    /**
     * Find knowledge entries by task type.
     *
     * Enables querying like "show me all learnings from database migration tasks"
     * or "what do we know about refactoring tasks?"
     *
     * @param taskType The task type to filter by
     * @param limit Maximum number of results to return
     * @return Result with knowledge entries for this task type
     */
    suspend fun findKnowledgeByTaskType(
        taskType: String,
        limit: Int = 10,
    ): Result<List<KnowledgeEntry>>

    /**
     * Find knowledge entries by tag.
     *
     * Tags provide categorical organization: "testing", "validation",
     * "database", "performance", etc. This enables targeted retrieval
     * of domain-specific learnings.
     *
     * @param tag The tag to filter by
     * @param limit Maximum number of results to return
     * @return Result with tagged knowledge entries
     */
    suspend fun findKnowledgeByTag(
        tag: String,
        limit: Int = 10,
    ): Result<List<KnowledgeEntry>>

    /**
     * Find knowledge entries by multiple tags (OR matching).
     *
     * Returns entries that match ANY of the provided tags.
     *
     * @param tags The tags to filter by
     * @param limit Maximum number of results to return
     * @return Result with knowledge entries matching any tag
     */
    suspend fun findKnowledgeByTags(
        tags: List<String>,
        limit: Int = 10,
    ): Result<List<KnowledgeEntry>>

    /**
     * Find knowledge entries within a timestamp range.
     *
     * Useful for temporal queries: "What did we learn last week?"
     * or "Show me historical learnings from the past month"
     *
     * @param fromTimestamp Start of the time range (inclusive)
     * @param toTimestamp End of the time range (inclusive)
     * @return Result with knowledge entries in the time range
     */
    suspend fun findKnowledgeByTimeRange(
        fromTimestamp: Instant,
        toTimestamp: Instant,
    ): Result<List<KnowledgeEntry>>

    /**
     * Find knowledge entries using multi-dimensional context filtering.
     *
     * This is the most powerful query—combining type, task type, tags,
     * time range, and complexity filters. Enables queries like:
     * "Show me successful outcomes from database tasks in the past week,
     * tagged with 'migration' or 'schema'"
     *
     * All parameters are optional—null values are ignored.
     *
     * @param knowledgeType Optional type filter
     * @param taskType Optional task type filter
     * @param tags Optional tags to match (OR matching)
     * @param complexityLevel Optional complexity filter
     * @param fromTimestamp Optional start time
     * @param toTimestamp Optional end time
     * @param limit Maximum number of results
     * @return Result with matching knowledge entries
     */
    suspend fun searchKnowledgeByContext(
        knowledgeType: KnowledgeType? = null,
        taskType: String? = null,
        tags: List<String>? = null,
        complexityLevel: String? = null,
        fromTimestamp: Instant? = null,
        toTimestamp: Instant? = null,
        limit: Int = 10,
    ): Result<List<KnowledgeEntry>>

    /**
     * Get a specific knowledge entry by ID.
     *
     * @param id The knowledge entry ID
     * @return Result with the knowledge entry or null if not found
     */
    suspend fun getKnowledgeById(id: String): Result<KnowledgeEntry?>

    /**
     * Get all tags associated with a knowledge entry.
     *
     * @param knowledgeId The knowledge entry ID
     * @return Result with list of tags
     */
    suspend fun getTagsForKnowledge(knowledgeId: String): Result<List<String>>
}

/**
 * Discriminator for the Knowledge sealed class types.
 */
enum class KnowledgeType {
    FROM_IDEA,
    FROM_OUTCOME,
    FROM_PERCEPTION,
    FROM_PLAN,
    FROM_TASK,
}

/**
 * A stored knowledge entry with associated metadata.
 *
 * This is the denormalized view for querying—it contains the key
 * information from a Knowledge object in a form that's easy to
 * query and display, with additional context like tags and task type.
 */
data class KnowledgeEntry(
    val id: String,
    val knowledgeType: KnowledgeType,
    val approach: String,
    val learnings: String,
    val timestamp: Instant,

    // Type-specific source IDs (only one will be non-null)
    val ideaId: String? = null,
    val outcomeId: String? = null,
    val perceptionId: String? = null,
    val planId: String? = null,
    val taskId: String? = null,

    // Contextual metadata
    val taskType: String? = null,
    val complexityLevel: String? = null,
    val tags: List<String> = emptyList(),
)
