package link.socket.ampere.api.internal

import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.toBelief
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.api.AmpereExperimentalApi
import link.socket.ampere.api.service.FieldService
import link.socket.ampere.commitment.CommitmentStore
import link.socket.ampere.commitment.SqlDelightCommitmentStore
import link.socket.ampere.db.Database
import link.socket.ampere.field.Action
import link.socket.ampere.field.ActionId
import link.socket.ampere.field.ActionResult
import link.socket.ampere.field.ActionState
import link.socket.ampere.field.Belief
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Field
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Relationship
import link.socket.ampere.field.Subject
import link.socket.ampere.goal.GoalStatus
import link.socket.ampere.goal.GoalStore
import link.socket.ampere.goal.SqlDelightGoalStore
import link.socket.ampere.observe.ObservationStore
import link.socket.ampere.observe.SqlDelightObservationStore
import link.socket.ampere.subject.RelationshipStore
import link.socket.ampere.subject.SqlDelightRelationshipStore
import link.socket.ampere.subject.SqlDelightSubjectStore
import link.socket.ampere.subject.SubjectStore
import link.socket.ampere.subject.subjectRef

/**
 * The SDK's Field read: one pass over the W2 stores, for one viewer (AMPR-439).
 *
 * The log's newest sequence is read first, so the snapshot names where in the log it was taken;
 * then the Subjects, every edge touching one of them (from either end, gathered with the store's
 * own `from` / `to` reads), each Subject's goals, the beliefs the viewer may see, the
 * Observations it may see (the Environment branch, AMPR-447), and every open commitment
 * (AMPR-449). Each viewer predicate is its own store's query, ahead of its limit; `beliefLimit`
 * bounds the beliefs and the Observations separately, since an Observation is a belief that lives
 * in another table. Commitments and Actions carry no sensitivity and are the same in every view.
 *
 * A belief is about the root goal of the run its row was written under, when that run formed a
 * goal tree, and about its holder otherwise — a lesson with no goal is self-knowledge. A row
 * with no provenance is counted on [Field.unmappedBeliefs] and left out.
 *
 * The taken Actions are the one branch not read from a store (AMPR-448, F20). The retype of the
 * tool events is fold-side (AMPR-328 Q5), so the newest [actionLimit] `ToolExecutionCompleted`
 * events are read from the log, newest first, and each is paired with its `ToolExecutionStarted`
 * by `invocationId`; see [takenActions].
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
    private val observations: ObservationStore,
    private val events: EventRepository,
    private val commitments: CommitmentStore,
    private val actionLimit: Int = DEFAULT_ACTION_LIMIT,
) : FieldService {

    init {
        require(actionLimit >= 0) { "actionLimit must not be negative, was $actionLimit" }
    }

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
        val rootGoals = mutableMapOf<String, Ref<Goal>?>()
        val beliefs = mutableListOf<Belief>()
        var unmapped = 0
        for (entry in visible) {
            val about = aboutOf(entry, rootGoals).getOrElse { return Result.failure(it) }
            val belief = entry.toBelief(about).getOrNull()
            if (belief != null) beliefs += belief else unmapped++
        }

        val observed = observations.visibleTo(viewer, beliefLimit).getOrElse { return Result.failure(it) }
        val open = commitments.withStatus(Commitment.Status.OPEN).getOrElse { return Result.failure(it) }
        val taken = takenActions(rootGoals).getOrElse { return Result.failure(it) }

        return Result.success(
            Field(
                viewer = viewer,
                asOfSequence = asOfSequence,
                subjects = listed,
                relationships = edges.toList(),
                goals = held,
                beliefs = beliefs,
                observations = observed,
                unmappedBeliefs = unmapped,
                commitments = open,
                actions = taken,
            ),
        )
    }

    /** What [entry] is about: its run's root goal when there is one, else its holder. */
    private suspend fun aboutOf(
        entry: KnowledgeEntry,
        rootGoals: MutableMap<String, Ref<Goal>?>,
    ): Result<Ref<Primitive>> {
        val runId = entry.runId ?: return Result.success(entry.holder)
        val root = rootGoalOf(runId, rootGoals).getOrElse { return Result.failure(it) }
        return Result.success(root ?: entry.holder)
    }

    /** The root of the goal tree [runId] formed, or `null` when it formed none; read once per run. */
    private suspend fun rootGoalOf(runId: String, rootGoals: MutableMap<String, Ref<Goal>?>): Result<Ref<Goal>?> {
        if (runId !in rootGoals) {
            val formed = goals.forRun(runId).getOrElse { return Result.failure(it) }
            rootGoals[runId] = formed.singleOrNull { it.goal.parent == null }?.id
        }
        return Result.success(rootGoals[runId])
    }

    /**
     * The newest taken Actions, newest first: each tool call the log still holds both halves of,
     * read as the [Action] it was (AMPR-448, F20). No event class carries the retype (Q5).
     *
     * One Action per `ToolExecutionCompleted`, paired with its `ToolExecutionStarted` by
     * `invocationId`. The actor is the start's source — the deciding agent's door — and the
     * executor its `executorId` (F17, AMPR-443). The goal is the root goal of the run the start's
     * envelope names, or `null` outside a run or for a run that formed no tree. The description is
     * the tool's name. The provenance is the start, the event that announced the Action. The
     * result is [ActionResult.Succeeded] or [ActionResult.Failed] with the completion's own error
     * message, `null` when it gave none. Reversibility and cost are `null`: nothing records either.
     *
     * Left out, rather than guessed at:
     * - a held completion: the call is waiting on a person, so it has not been taken (AMPR-425);
     * - a completion whose start is not among the newest `actionLimit × START_WINDOW` starts at or
     *   before the newest completion — rotated out of the log under its budget, or a call that
     *   outlasted that many others. Without the start there is no provenance to give the Action.
     */
    private suspend fun takenActions(rootGoals: MutableMap<String, Ref<Goal>?>): Result<List<Action>> {
        if (actionLimit == 0) return Result.success(emptyList())
        val completions = events
            .getLatestEventsByType(ToolEvent.ToolExecutionCompleted.EVENT_TYPE, limit = actionLimit.toLong())
            .getOrElse { return Result.failure(it) }
        val newest = completions.firstOrNull() ?: return Result.success(emptyList())
        val starts = events
            .getLatestEventsByType(
                ToolEvent.ToolExecutionStarted.EVENT_TYPE,
                limit = actionLimit.toLong() * START_WINDOW,
                upToSequence = newest.sequence,
            )
            .getOrElse { return Result.failure(it) }
        val startsByInvocation = HashMap<String, StoredEvent>()
        for (stored in starts) {
            val started = stored.event as? ToolEvent.ToolExecutionStarted ?: continue
            startsByInvocation[started.invocationId] = stored
        }

        val actions = mutableListOf<Action>()
        for (stored in completions) {
            val completed = stored.event as? ToolEvent.ToolExecutionCompleted ?: continue
            if (completed.held) continue
            val startEnvelope = startsByInvocation[completed.invocationId] ?: continue
            val started = startEnvelope.event as ToolEvent.ToolExecutionStarted
            val goal = startEnvelope.runId?.let { runId ->
                rootGoalOf(runId, rootGoals).getOrElse { return Result.failure(it) }
            }
            actions += Action(
                id = ActionId(completed.invocationId),
                actor = started.eventSource.subjectRef(),
                executor = started.executorId,
                goal = goal,
                description = started.toolName,
                state = ActionState.TAKEN,
                reversibility = null,
                cost = null,
                result = completed.result(),
                provenance = Provenance(createdBy = started.eventId, createdAt = startEnvelope.recordedAt),
            )
        }
        return Result.success(actions)
    }

    private fun ToolEvent.ToolExecutionCompleted.result(): ActionResult =
        if (success) ActionResult.Succeeded else ActionResult.Failed(reason = errorMessage)

    private fun GoalStatus.toGoalState(): Field.GoalState = when (this) {
        GoalStatus.OPEN -> Field.GoalState.OPEN
        GoalStatus.COMPLETED -> Field.GoalState.COMPLETED
        GoalStatus.ABANDONED -> Field.GoalState.ABANDONED
    }

    companion object {
        /** How many taken Actions a snapshot carries: the newest this many completed tool calls. */
        const val DEFAULT_ACTION_LIMIT: Int = 100

        /**
         * How many starts per completion the pairing reads. A start precedes its completion, and
         * the only starts between the oldest one wanted and the newest completion that are not
         * wanted belong to calls that overlapped it or never completed; twice the window covers
         * both unless more calls overlapped than the window holds.
         */
        private const val START_WINDOW: Long = 2

        /** A Field read over [database]'s stores, anchored to [events]'s log. */
        fun over(
            database: Database,
            knowledge: KnowledgeRepository,
            events: EventRepository,
            actionLimit: Int = DEFAULT_ACTION_LIMIT,
        ): DefaultFieldService = DefaultFieldService(
            subjects = SqlDelightSubjectStore(database),
            relationships = SqlDelightRelationshipStore(database),
            goals = SqlDelightGoalStore(database),
            knowledge = knowledge,
            observations = SqlDelightObservationStore(database),
            events = events,
            commitments = SqlDelightCommitmentStore(database),
            actionLimit = actionLimit,
        )
    }
}
