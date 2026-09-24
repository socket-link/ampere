package link.socket.ampere.api.internal

import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.api.model.KnowledgeProvenance
import link.socket.ampere.api.service.KnowledgeService

internal class DefaultKnowledgeService(
    private val knowledgeRepository: KnowledgeRepository,
) : KnowledgeService {

    override suspend fun store(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
        runId: RunId?,
    ): Result<KnowledgeEntry> = knowledgeRepository.storeKnowledge(
        knowledge = knowledge,
        tags = tags,
        taskType = taskType,
        complexityLevel = complexityLevel,
        runId = runId,
    )

    override suspend fun get(id: String): Result<KnowledgeEntry?> =
        knowledgeRepository.getKnowledgeById(id)

    override suspend fun recall(query: String, limit: Int): Result<List<KnowledgeEntry>> =
        knowledgeRepository.findSimilarKnowledge(query, limit)

    override suspend fun search(
        query: String?,
        type: KnowledgeType?,
        taskType: String?,
        tags: List<String>?,
        limit: Int,
    ): Result<List<KnowledgeEntry>> {
        // Use contextual search when filters are provided
        if (type != null || taskType != null || !tags.isNullOrEmpty()) {
            return knowledgeRepository.searchKnowledgeByContext(
                knowledgeType = type,
                taskType = taskType,
                tags = tags,
                limit = limit,
            )
        }
        // Fall back to full-text search when only query is provided
        return knowledgeRepository.findSimilarKnowledge(query ?: "", limit)
    }

    override suspend fun tags(knowledgeId: String): Result<List<String>> =
        knowledgeRepository.getTagsForKnowledge(knowledgeId)

    // One hop, and deliberately so: a knowledge row names the element it was distilled
    // from and nothing else. There is no parent-entry column, and no table holds the
    // Idea/Outcome/Perception/Plan/Task a source id addresses, so there is no second step
    // to take. Walking source ids through getKnowledgeById — as this did before AMPR-350 —
    // reads them as knowledge ids, which they never are.
    override suspend fun provenance(knowledgeId: String): Result<KnowledgeProvenance> =
        knowledgeRepository.getKnowledgeById(knowledgeId).mapCatching { entry ->
            val found = requireNotNull(entry) { "Knowledge not found: $knowledgeId" }
            KnowledgeProvenance(
                entry = found,
                sourceType = found.knowledgeType,
                sourceId = found.sourceId(),
            )
        }
}

/**
 * The id of the cognitive element this entry was distilled from, read from the column
 * its own [KnowledgeType] discriminator points at. Null only for a row that recorded no
 * source id.
 */
private fun KnowledgeEntry.sourceId(): String? = when (knowledgeType) {
    KnowledgeType.FROM_IDEA -> ideaId
    KnowledgeType.FROM_OUTCOME -> outcomeId
    KnowledgeType.FROM_PERCEPTION -> perceptionId
    KnowledgeType.FROM_PLAN -> planId
    KnowledgeType.FROM_TASK -> taskId
}
