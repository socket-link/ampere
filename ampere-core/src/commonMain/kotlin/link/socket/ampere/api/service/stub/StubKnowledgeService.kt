package link.socket.ampere.api.service.stub

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.knowledge.BeliefConfidence
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.api.model.KnowledgeProvenance
import link.socket.ampere.api.service.KnowledgeService
import link.socket.ampere.field.Sensitivity

/**
 * Stub implementation of [KnowledgeService] for testing and parallel development.
 *
 * Store returns a stub entry carrying the confidence and sensitivity it was given, but no
 * `createdByEvent`, because the stub publishes nothing; recall and search return empty lists;
 * provenance reports not-found, since the stub keeps no rows and no events to walk.
 */
class StubKnowledgeService : KnowledgeService {

    private var knowledgeCounter = 0

    override suspend fun store(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
        runId: RunId?,
        confidence: BeliefConfidence?,
        sensitivity: Sensitivity,
        causedBy: EventId?,
    ): Result<KnowledgeEntry> {
        knowledgeCounter++
        return Result.success(
            KnowledgeEntry(
                id = "stub-knowledge-$knowledgeCounter",
                knowledgeType = KnowledgeType.FROM_OUTCOME,
                approach = knowledge.approach,
                learnings = knowledge.learnings,
                timestamp = Clock.System.now(),
                confidence = confidence?.value,
                sensitivity = sensitivity,
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
