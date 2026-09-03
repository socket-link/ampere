package link.socket.ampere.agents.domain.knowledge

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.db.Database
import link.socket.ampere.db.fts.FtsAvailability
import link.socket.ampere.db.fts.FtsSchema
import link.socket.ampere.db.fts.searchKnowledgeByText
import link.socket.ampere.db.memory.KnowledgeStore
import link.socket.ampere.db.memory.KnowledgeStoreQueries
import link.socket.ampere.util.ioDispatcher

/**
 * SQLDelight-backed implementation of KnowledgeRepository.
 *
 * This stores Knowledge entries in a searchable database with full-text
 * search capabilities for finding semantically similar past learnings.
 *
 * The implementation uses a discriminator pattern to handle the polymorphic
 * Knowledge sealed class, storing all subtypes in a single table with
 * type-specific foreign key IDs.
 *
 * @param driver The driver backing [database]. Required to run the FTS5 `KnowledgeFts` query
 * (see [link.socket.ampere.db.fts.FtsSchema]), which can no longer be a SQLDelight-typed query
 * now that the virtual table isn't declared in the `.sq` schema. When `null` (the default, kept
 * for source compatibility with existing callers), [findSimilarKnowledge] always uses the
 * `LIKE` fallback rather than attempting FTS.
 */
class KnowledgeRepositoryImpl(
    private val database: Database,
    private val driver: SqlDriver? = null,
) : KnowledgeRepository {

    private val queries: KnowledgeStoreQueries
        get() = database.knowledgeStoreQueries

    // Installed lazily rather than eagerly in the constructor so that repositories built from
    // a driver whose schema hasn't been created yet (or in tests that never call
    // findSimilarKnowledge) don't pay for it. Idempotent: safe to race with another repository
    // installing the same driver's FTS schema.
    private val ftsAvailability: FtsAvailability? by lazy {
        driver?.let(FtsSchema::install)
    }

    override suspend fun storeKnowledge(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
        runId: RunId?,
    ): Result<KnowledgeEntry> = withContext(ioDispatcher) {
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
            if (runId != null) {
                queries.insertKnowledgeWithRunId(
                    id = id,
                    knowledge_type = knowledgeType.name,
                    approach = knowledge.approach,
                    learnings = knowledge.learnings,
                    timestamp = knowledge.timestamp.toEpochMilliseconds(),
                    run_id = runId,
                    idea_id = ideaId,
                    outcome_id = outcomeId,
                    perception_id = perceptionId,
                    plan_id = planId,
                    task_id = taskId,
                    task_type = taskType,
                    complexity_level = complexityLevel,
                )
            } else {
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
            }

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
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
        runCatching {
            // Convert description to FTS5 query format: split into keywords and join with OR
            // for broader matching.
            val keywords = description
                .split(Regex("\\s+"))
                .filter { it.length > 2 } // Skip very short words
                .joinToString(" OR ")

            if (keywords.isEmpty()) {
                emptyList()
            } else {
                // Route on the recorded FTS availability instead of attempting FTS and
                // catching a failure on every call — a driver without the fts5 module fails
                // identically every time, so there's nothing to gain from retrying it.
                when (ftsAvailability) {
                    is FtsAvailability.Available ->
                        driver!!.searchKnowledgeByText(keywords, limit.toLong())
                            .map { row -> mapRowToKnowledgeEntry(row) }

                    is FtsAvailability.Unavailable, null ->
                        queries.searchKnowledgeByTextLike(description, description, limit.toLong())
                            .executeAsList()
                            .map { row -> mapRowToKnowledgeEntry(row) }
                }
            }
        }
    }

    override suspend fun findKnowledgeByType(
        knowledgeType: KnowledgeType,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
        runCatching {
            queries.findKnowledgeByType(knowledgeType.name, limit.toLong())
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun findKnowledgeByTaskType(
        taskType: String,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
        runCatching {
            queries.findKnowledgeByTaskType(taskType, limit.toLong())
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun findKnowledgeByTag(
        tag: String,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
        runCatching {
            queries.findKnowledgeByTag(tag, limit.toLong())
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun findKnowledgeByTags(
        tags: List<String>,
        limit: Int,
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
        runCatching {
            queries.findKnowledgeByTags(tags, limit.toLong())
                .executeAsList()
                .map { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun findKnowledgeByTimeRange(
        fromTimestamp: Instant,
        toTimestamp: Instant,
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
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
    ): Result<List<KnowledgeEntry>> = withContext(ioDispatcher) {
        runCatching {
            // Use different query based on whether tags are provided
            if (!tags.isNullOrEmpty()) {
                queries.searchKnowledgeByContextWithTags(
                    knowledge_type = knowledgeType?.name,
                    task_type = taskType,
                    complexity_level = complexityLevel,
                    from_timestamp = fromTimestamp?.toEpochMilliseconds(),
                    to_timestamp = toTimestamp?.toEpochMilliseconds(),
                    tags = tags,
                    limit = limit.toLong(),
                )
                    .executeAsList()
                    .map { row -> mapRowToKnowledgeEntry(row) }
            } else {
                queries.searchKnowledgeByContextNoTags(
                    knowledge_type = knowledgeType?.name,
                    task_type = taskType,
                    complexity_level = complexityLevel,
                    from_timestamp = fromTimestamp?.toEpochMilliseconds(),
                    to_timestamp = toTimestamp?.toEpochMilliseconds(),
                    limit = limit.toLong(),
                )
                    .executeAsList()
                    .map { row -> mapRowToKnowledgeEntry(row) }
            }
        }
    }

    override suspend fun getKnowledgeById(id: String): Result<KnowledgeEntry?> = withContext(ioDispatcher) {
        runCatching {
            queries.getKnowledgeById(id)
                .executeAsOneOrNull()
                ?.let { row -> mapRowToKnowledgeEntry(row) }
        }
    }

    override suspend fun getTagsForKnowledge(knowledgeId: String): Result<List<String>> = withContext(ioDispatcher) {
        runCatching {
            queries.getTagsForKnowledge(knowledgeId)
                .executeAsList()
        }
    }

    /**
     * Map a database row to a KnowledgeEntry domain object.
     * This includes fetching associated tags.
     */
    private fun mapRowToKnowledgeEntry(row: KnowledgeStore): KnowledgeEntry {
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
