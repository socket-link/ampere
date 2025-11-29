package link.socket.ampere.agents.core.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.db.Database
import link.socket.ampere.db.memory.KnowledgeStoreQueries

/**
 * SQLDelight-backed implementation of KnowledgeRepository.
 *
 * This stores Knowledge entries in a searchable database with full-text
 * search capabilities for finding semantically similar past learnings.
 *
 * The implementation uses a discriminator pattern to handle the polymorphic
 * Knowledge sealed class, storing all subtypes in a single table with
 * type-specific foreign key IDs.
 */
class KnowledgeRepositoryImpl(
    private val database: Database,
) : KnowledgeRepository {

    private val queries: KnowledgeStoreQueries
        get() = database.knowledgeStoreQueries

    override suspend fun storeKnowledge(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
    ): Result<KnowledgeEntry> = withContext(Dispatchers.IO) {
        runCatching {
            // Generate ID based on knowledge type and source ID
            val id = when (knowledge) {
                is Knowledge.FromIdea -> generateUUID("knowledge-idea", knowledge.ideaId)
                is Knowledge.FromOutcome -> generateUUID("knowledge-outcome", knowledge.outcomeId)
                is Knowledge.FromPerception -> generateUUID("knowledge-perception", knowledge.perceptionId)
                is Knowledge.FromPlan -> generateUUID("knowledge-plan", knowledge.planId)
                is Knowledge.FromTask -> generateUUID("knowledge-task", knowledge.taskId)
            }

            val knowledgeType = when (knowledge) {
                is Knowledge.FromIdea -> KnowledgeType.FROM_IDEA
                is Knowledge.FromOutcome -> KnowledgeType.FROM_OUTCOME
                is Knowledge.FromPerception -> KnowledgeType.FROM_PERCEPTION
                is Knowledge.FromPlan -> KnowledgeType.FROM_PLAN
                is Knowledge.FromTask -> KnowledgeType.FROM_TASK
            }

            // Extract type-specific IDs
            val (ideaId, outcomeId, perceptionId, planId, taskId) = when (knowledge) {
                is Knowledge.FromIdea -> Tuple5(knowledge.ideaId, null, null, null, null)
                is Knowledge.FromOutcome -> Tuple5(null, knowledge.outcomeId, null, null, null)
                is Knowledge.FromPerception -> Tuple5(null, null, knowledge.perceptionId, null, null)
                is Knowledge.FromPlan -> Tuple5(null, null, null, knowledge.planId, null)
                is Knowledge.FromTask -> Tuple5(null, null, null, null, knowledge.taskId)
            }

            // Insert knowledge entry
            queries.insertKnowledge(
                id = id,
                knowledge_type = knowledgeType.name,
                approach = knowledge.approach,
                learnings = knowledge.learnings,
                timestamp = knowledge.timestamp.toEpochMilliseconds(),
                idea_id = ideaId,
                outcome_id = outcomeId,
                perception_id = perceptionId,
                plan_id = planId,
                task_id = taskId,
                task_type = taskType,
                complexity_level = complexityLevel,
            )

            // Insert tags
            tags.forEach { tag ->
                queries.insertKnowledgeTag(
                    knowledge_id = id,
                    tag = tag,
                )
            }

            KnowledgeEntry(
                id = id,
                knowledgeType = knowledgeType,
                approach = knowledge.approach,
                learnings = knowledge.learnings,
                timestamp = knowledge.timestamp,
                ideaId = ideaId,
                outcomeId = outcomeId,
                perceptionId = perceptionId,
                planId = planId,
                taskId = taskId,
                taskType = taskType,
                complexityLevel = complexityLevel,
                tags = tags,
            )
        }
    }

    override suspend fun findSimilarKnowledge(
        description: String,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            // Try FTS search first, fall back to LIKE if FTS fails
            try {
                // Convert description to FTS5 query format
                // Split into keywords and join with OR for broader matching
                val keywords = description
                    .split(Regex("\\s+"))
                    .filter { it.length > 2 } // Skip very short words
                    .joinToString(" OR ")

                if (keywords.isNotEmpty()) {
                    queries.searchKnowledgeByText(keywords, limit.toLong())
                        .executeAsList()
                        .map { row -> mapRowToKnowledgeEntry(row) }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                // Fall back to LIKE-based search if FTS fails
                queries.searchKnowledgeByTextLike(description, description, limit.toLong())
                    .executeAsList()
                    .map { row -> mapRowToKnowledgeEntry(row) }
            }
        }
    }

    override suspend fun findKnowledgeByType(
        knowledgeType: KnowledgeType,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            queries.findKnowledgeByType(knowledgeType.name, limit.toLong())
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun findKnowledgeByTaskType(
        taskType: String,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            queries.findKnowledgeByTaskType(taskType, limit.toLong())
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun findKnowledgeByTag(
        tag: String,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            queries.findKnowledgeByTag(tag, limit.toLong())
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun findKnowledgeByTags(
        tags: List<String>,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            queries.findKnowledgeByTags(tags, limit.toLong())
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun findKnowledgeByTimeRange(
        fromTimestamp: Instant,
        toTimestamp: Instant,
    ): Result<List<KnowledgeEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            queries.findKnowledgeByTimeRange(
                fromTimestamp.toEpochMilliseconds(),
                toTimestamp.toEpochMilliseconds(),
            )
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun searchKnowledgeByContext(
        knowledgeType: KnowledgeType?,
        taskType: String?,
        tags: List<String>?,
        complexityLevel: String?,
        fromTimestamp: Instant?,
        toTimestamp: Instant?,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            queries.searchKnowledgeByContext(
                knowledge_type = knowledgeType?.name,
                task_type = taskType,
                complexity_level = complexityLevel,
                from_timestamp = fromTimestamp?.toEpochMilliseconds(),
                to_timestamp = toTimestamp?.toEpochMilliseconds(),
                tags = tags ?: emptyList(),
                limit = limit.toLong(),
            )
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun getKnowledgeById(id: String): Result<KnowledgeEntry?> = withContext(Dispatchers.IO) {
        runCatching {
            queries.getKnowledgeById(id)
                .executeAsOneOrNull()
                ?.let { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun getTagsForKnowledge(knowledgeId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            queries.getTagsForKnowledge(knowledgeId)
                .executeAsList()
        }
    }

    /**
     * Map a database row to a KnowledgeEntry domain object.
     * This includes fetching associated tags.
     */
    private fun mapRowToKnowledgeEntry(row: link.socket.ampere.db.memory.KnowledgeStore): KnowledgeEntry {
        // Fetch tags for this knowledge entry
        val tags = queries.getTagsForKnowledge(row.id).executeAsList()

        return KnowledgeEntry(
            id = row.id,
            knowledgeType = KnowledgeType.valueOf(row.knowledge_type),
            approach = row.approach,
            learnings = row.learnings,
            timestamp = Instant.fromEpochMilliseconds(row.timestamp),
            ideaId = row.idea_id,
            outcomeId = row.outcome_id,
            perceptionId = row.perception_id,
            planId = row.plan_id,
            taskId = row.task_id,
            taskType = row.task_type,
            complexityLevel = row.complexity_level,
            tags = tags,
        )
    }
}

/**
 * Helper data class for unpacking type-specific IDs.
 */
private data class Tuple5<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
)
