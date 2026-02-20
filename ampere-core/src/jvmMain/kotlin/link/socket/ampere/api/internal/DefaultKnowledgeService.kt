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
}
