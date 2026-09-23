package link.socket.ampere.api.service.stub

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.api.model.KnowledgeProvenance
import link.socket.ampere.api.service.KnowledgeService

/**
 * Stub implementation of [KnowledgeService] for testing and parallel development.
 *
 * Store returns a stub entry; recall and search return empty lists; provenance reports
 * not-found, since the stub keeps nothing to trace.
 */
class StubKnowledgeService : KnowledgeService {

    private var knowledgeCounter = 0

    override suspend fun store(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
        runId: RunId?,
    ): Result<KnowledgeEntry> {
        knowledgeCounter++
        return Result.success(
            KnowledgeEntry(
                id = "stub-knowledge-$knowledgeCounter",
                knowledgeType = KnowledgeType.FROM_OUTCOME,
                approach = knowledge.approach,
                learnings = knowledge.learnings,
                timestamp = Clock.System.now(),
            ),
        )
    }

    override suspend fun get(id: String): Result<KnowledgeEntry?> =
        Result.success(null)

    override suspend fun recall(query: String, limit: Int): Result<List<KnowledgeEntry>> =
        Result.success(emptyList())

    override suspend fun search(
        query: String?,
        type: KnowledgeType?,
        taskType: String?,
        tags: List<String>?,
        limit: Int,
    ): Result<List<KnowledgeEntry>> =
        Result.success(emptyList())

    override suspend fun tags(knowledgeId: String): Result<List<String>> =
        Result.success(emptyList())

    override suspend fun provenance(knowledgeId: String): Result<KnowledgeProvenance> =
        Result.failure(IllegalArgumentException("Stub: knowledge not found: $knowledgeId"))
}
