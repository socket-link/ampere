package link.socket.ampere.api.internal

import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.api.service.KnowledgeService

internal class DefaultKnowledgeService(
    private val knowledgeRepository: KnowledgeRepository,
) : KnowledgeService {

    override suspend fun store(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
    ): Result<KnowledgeEntry> = knowledgeRepository.storeKnowledge(
        knowledge = knowledge,
        tags = tags,
        taskType = taskType,
        complexityLevel = complexityLevel,
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

    override suspend fun provenance(knowledgeId: String): Result<List<KnowledgeEntry>> {
        return try {
            val entry = knowledgeRepository.getKnowledgeById(knowledgeId).getOrThrow()
                ?: return Result.failure(IllegalArgumentException("Knowledge not found: $knowledgeId"))

            // Build provenance trail by following source IDs
            val trail = mutableListOf(entry)
            var current = entry

            // Follow the chain: each entry may reference an idea, outcome, or perception
            // that in turn has associated knowledge entries
            while (true) {
                val sourceId = current.ideaId ?: current.outcomeId ?: current.perceptionId ?: break
                val source = knowledgeRepository.getKnowledgeById(sourceId).getOrNull() ?: break
                trail.add(source)
                current = source
            }

            Result.success(trail)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
