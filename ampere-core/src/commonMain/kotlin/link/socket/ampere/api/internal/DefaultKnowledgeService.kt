package link.socket.ampere.api.internal

import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.knowledge.BeliefConfidence
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.api.model.KnowledgeProvenance
import link.socket.ampere.api.service.KnowledgeService
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.subject.asSubjectRef

/**
 * The SDK's knowledge service, writing and reading as the Subject its door belongs to.
 *
 * A store goes through the same [AgentMemoryService] path an agent's does, so it publishes a
 * `KnowledgeStored` through [eventApi] and the row records that event (F1, F11): there is no
 * second, event-less write path. Reads name the door's identity as their viewer (F12).
 *
 * @param eventRepository the store a [provenance] walk reads envelopes from — the one
 *   [eventApi] persists into.
 */
internal class DefaultKnowledgeService(
    private val knowledgeRepository: KnowledgeRepository,
    eventApi: AgentEventApi,
    private val eventRepository: EventRepository,
) : KnowledgeService {

    private val memory = AgentMemoryService(
        agentId = eventApi.agentId,
        knowledgeRepository = knowledgeRepository,
        eventApi = eventApi,
    )

    private val viewer = eventApi.agentId.asSubjectRef()

    override suspend fun store(
        knowledge: Knowledge,
        tags: List<String>,
        taskType: String?,
        complexityLevel: String?,
        runId: RunId?,
        confidence: BeliefConfidence?,
        sensitivity: Sensitivity,
        causedBy: EventId?,
    ): Result<KnowledgeEntry> = memory.store(
        knowledge = knowledge,
        tags = tags,
        taskType = taskType,
        complexityLevel = complexityLevel,
        runId = runId,
        confidence = confidence,
        sensitivity = sensitivity,
        causedBy = causedBy,
    )

    override suspend fun get(id: String): Result<KnowledgeEntry?> =
        knowledgeRepository.getKnowledgeById(id, viewer = viewer)

    override suspend fun recall(query: String, limit: Int): Result<List<KnowledgeEntry>> =
        knowledgeRepository.findSimilarKnowledge(query, limit, viewer = viewer)

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
                viewer = viewer,
            )
        }
        // Fall back to full-text search when only query is provided
        return knowledgeRepository.findSimilarKnowledge(query ?: "", limit, viewer = viewer)
    }

    override suspend fun tags(knowledgeId: String): Result<List<String>> =
        knowledgeRepository.getTagsForKnowledge(knowledgeId)

    // The source hop is one fact and the event chain another. A knowledge row names the element
    // it was distilled from and nothing else — no parent entry, and no table holds the element —
    // so that hop is never followed. What *is* followed is the row's `created_by_event` and then
    // each envelope's `caused_by`: provenance walks events, never knowledge-to-knowledge strings
    // (F11).
    override suspend fun provenance(knowledgeId: String): Result<KnowledgeProvenance> =
        knowledgeRepository.getKnowledgeById(knowledgeId, viewer = viewer).mapCatching { entry ->
            val found = requireNotNull(entry) { "Knowledge not found: $knowledgeId" }
            val walk = walkCausedBy(found.createdByEvent)
            KnowledgeProvenance(
                entry = found,
                sourceType = found.knowledgeType,
                sourceId = found.sourceId(),
                events = walk.events,
                unresolvedEventId = walk.unresolved,
            )
        }

    private class Walk(val events: List<StoredEvent>, val unresolved: EventId?)

    /**
     * Follow `caused_by` from [start] until an event names no cause, an id has no row, or the
     * chain revisits an id. A store failure fails the walk rather than shortening it, so a short
     * chain is always one the store actually holds.
     */
    private suspend fun walkCausedBy(start: EventId?): Walk {
        val events = mutableListOf<StoredEvent>()
        val seen = mutableSetOf<EventId>()
        var next = start
        while (next != null && seen.add(next) && events.size < MAX_PROVENANCE_DEPTH) {
            val stored = eventRepository.getStoredEventById(next).getOrThrow()
                ?: return Walk(events, unresolved = next)
            events += stored
            next = stored.causedBy
        }
        return Walk(events, unresolved = null)
    }

    private companion object {
        /** A guard, not a policy: no producer chains events anywhere near this deep. */
        const val MAX_PROVENANCE_DEPTH = 256
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
