package link.socket.ampere.api.internal

import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.toBelief
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.api.AmpereExperimentalApi
import link.socket.ampere.api.service.FieldService
import link.socket.ampere.db.Database
import link.socket.ampere.field.Belief
import link.socket.ampere.field.Field
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Relationship
import link.socket.ampere.field.Subject
import link.socket.ampere.goal.GoalStatus
import link.socket.ampere.goal.GoalStore
import link.socket.ampere.goal.SqlDelightGoalStore
import link.socket.ampere.subject.RelationshipStore
import link.socket.ampere.subject.SqlDelightRelationshipStore
import link.socket.ampere.subject.SqlDelightSubjectStore
import link.socket.ampere.subject.SubjectStore

/**
 * The SDK's Field read: one pass over the W2 stores, for one viewer (AMPR-439).
 *
 * The log's newest sequence is read first, so the snapshot names where in the log it was taken;
 * then the Subjects, every edge touching one of them (from either end, gathered with the store's
 * own `from` / `to` reads), each Subject's goals, and the beliefs the viewer may see. The viewer
 * predicate is the knowledge store's own query, ahead of its limit.
 *
 * A belief is about the root goal of the run its row was written under, when that run formed a
 * goal tree, and about its holder otherwise — a lesson with no goal is self-knowledge. A row
 * with no provenance is counted on [Field.unmappedBeliefs] and left out.
 *
 * Reads only. Any store's failure is the snapshot's failure: a Field with one branch silently
 * missing would read as a world where that branch is empty.
 */
@AmpereExperimentalApi
internal class DefaultFieldService(
    private val subjects: SubjectStore,
    private val relationships: RelationshipStore,
    private val goals: GoalStore,
    private val knowledge: KnowledgeRepository,
    private val events: EventRepository,
) : FieldService {

    override suspend fun snapshot(viewer: Ref<Subject>?, beliefLimit: Int): Result<Field> {
        // SQLite reads a negative LIMIT as no limit at all.
        if (beliefLimit < 0) {
            return Result.failure(IllegalArgumentException("beliefLimit must not be negative, was $beliefLimit"))
        }

        val asOfSequence = events.latestSequence().getOrElse { return Result.failure(it) }
        val listed = subjects.list().getOrElse { return Result.failure(it) }

        val edges = LinkedHashSet<Relationship<Primitive, Primitive>>()
        for (subject in listed) {
            edges += relationships.from(subject.id).getOrElse { return Result.failure(it) }
            edges += relationships.to(subject.id).getOrElse { return Result.failure(it) }
        }

        val held = mutableListOf<Field.HeldGoal>()
        for (subject in listed) {
            for (status in GoalStatus.entries) {
                goals.forSubject(subject.id, status).getOrElse { return Result.failure(it) }
                    .mapTo(held) { record -> Field.HeldGoal(record.goal, record.subject, status.toGoalState()) }
            }
        }

        val visible = knowledge.findKnowledgeVisibleTo(viewer, beliefLimit).getOrElse { return Result.failure(it) }
        val rootGoals = mutableMapOf<String, Ref<Primitive>?>()
        val beliefs = mutableListOf<Belief>()
        var unmapped = 0
        for (entry in visible) {
            val about = aboutOf(entry, rootGoals).getOrElse { return Result.failure(it) }
            val belief = entry.toBelief(about).getOrNull()
            if (belief != null) beliefs += belief else unmapped++
        }

        return Result.success(
            Field(
                viewer = viewer,
                asOfSequence = asOfSequence,
                subjects = listed,
                relationships = edges.toList(),
                goals = held,
                beliefs = beliefs,
                unmappedBeliefs = unmapped,
            ),
        )
    }

    /** What [entry] is about: its run's root goal when there is one, else its holder. */
    private suspend fun aboutOf(
        entry: KnowledgeEntry,
        rootGoals: MutableMap<String, Ref<Primitive>?>,
    ): Result<Ref<Primitive>> {
        val runId = entry.runId ?: return Result.success(entry.holder)
        if (runId !in rootGoals) {
            val formed = goals.forRun(runId).getOrElse { return Result.failure(it) }
            rootGoals[runId] = formed.singleOrNull { it.goal.parent == null }?.id
        }
        return Result.success(rootGoals[runId] ?: entry.holder)
    }

    private fun GoalStatus.toGoalState(): Field.GoalState = when (this) {
        GoalStatus.OPEN -> Field.GoalState.OPEN
        GoalStatus.COMPLETED -> Field.GoalState.COMPLETED
    }

    companion object {
        /** A Field read over [database]'s stores, anchored to [events]'s log. */
        fun over(
            database: Database,
            knowledge: KnowledgeRepository,
            events: EventRepository,
        ): DefaultFieldService = DefaultFieldService(
            subjects = SqlDelightSubjectStore(database),
            relationships = SqlDelightRelationshipStore(database),
            goals = SqlDelightGoalStore(database),
            knowledge = knowledge,
            events = events,
        )
    }
}
