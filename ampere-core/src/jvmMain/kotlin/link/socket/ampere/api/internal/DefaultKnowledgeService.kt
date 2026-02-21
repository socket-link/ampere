package link.socket.ampere.api.internal

import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
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

    override suspend fun recall(query: String, limit: Int): Result<List<KnowledgeEntry>> =
        knowledgeRepository.findSimilarKnowledge(query, limit)

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
