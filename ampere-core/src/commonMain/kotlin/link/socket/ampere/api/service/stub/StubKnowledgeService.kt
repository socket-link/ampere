package link.socket.ampere.api.service.stub

import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.api.service.KnowledgeService
import kotlinx.datetime.Clock

/**
 * Stub implementation of [KnowledgeService] for testing and parallel development.
 *
 * Store returns a stub entry; recall and provenance return empty lists.
 */
class StubKnowledgeService : KnowledgeService {

    private var knowledgeCounter = 0

    override suspend fun store(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
    ): Result<KnowledgeEntry> {
        knowledgeCounter++
        return Result.success(
            KnowledgeEntry(
                id = "stub-knowledge-$knowledgeCounter",
                knowledgeType = KnowledgeType.FROM_OUTCOME,
                approach = knowledge.approach,
                learnings = knowledge.learnings,
                timestamp = Clock.System.now(),
            )
        )
    }

    override suspend fun recall(query: String, limit: Int): Result<List<KnowledgeEntry>> =
        Result.success(emptyList())

    override suspend fun provenance(knowledgeId: String): Result<List<KnowledgeEntry>> =
        Result.success(emptyList())
}
