package link.socket.ampere.goal

import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.GoalEvent
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.canon.CanonEntity
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonWorkItem
import link.socket.ampere.db.Database
import link.socket.ampere.domain.arc.GoalNode
import link.socket.ampere.domain.arc.GoalTree
import link.socket.ampere.field.Environment
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Relationship
import link.socket.ampere.field.RelationshipKind
import link.socket.ampere.field.Subject
import link.socket.ampere.subject.RelationshipStore
import link.socket.ampere.subject.SqlDelightRelationshipStore
import link.socket.ampere.subject.SqlDelightSubjectStore
import link.socket.ampere.subject.SubjectRegistry
import link.socket.ampere.subject.asSubjectRef

/**
 * The Goal branch of the Field (AMPR-419, F8): the agent's own goals, persisted, and the
 * tracker's view of them linked as mirrors.
 *
 * [GoalStore] holds rows. This class is what makes a row a *goal on the record*: it publishes a
 * [GoalEvent] through [AgentEventApi.publish] first — under the run the goal belongs to, so the
 * run's trace carries it — and only then writes the row, with the event's id as its provenance.
 * A write whose event the door refused writes nothing and returns the door's failure.
 *
 * ## Whose goals
 *
 * Every goal this branch forms is held by the agent behind its door ([holder]), which is
 * registered as a Subject before its first goal so the holder always resolves. For an Arc run
 * that is the runtime's own door — the agent that decomposed the person's goal into a tree.
 *
 * ## Arc goal ids
 *
 * `GoalTreeBuilder` numbers every run's nodes from `goal-0`, so a node id names a goal only
 * within its run. On the branch a run's goal is [goalRef] — `<run id>/<node id>` — and a tree
 * read back with [tree] carries the node ids it was formed with.
 *
 * ## Mirrors
 *
 * A `CanonWorkItem`, `CanonProject` or `CanonMilestone` links to a goal through [mirror]: a
 * `Mirrors` edge from the entity's Link-qualified `environmentRef` (AMPR-418) to the goal,
 * recorded through the Subject registry like every other edge. Neither side owns the other.
 * [dependencies] turns a work item's `dependsOn` into a `Rule.Dependency` over the goals its
 * dependencies mirror; nothing evaluates it yet (W3).
 *
 * No weight, rank or priority is derived here (F28 is open).
 */
class GoalBranch(
    private val goals: GoalStore,
    private val relationships: RelationshipStore,
    private val registry: SubjectRegistry,
    private val eventApi: AgentEventApi,
) {

    /** Whose goals this branch forms: the agent behind its door. */
    val holder: Ref<Subject.Agent>
        get() = eventApi.agentId.asSubjectRef()

    /**
     * Put [tree] on the branch as [runId]'s goals and return the tree as the store now holds it.
     *
     * One `GoalFormed` per node, parents before children, then the row. Idempotent per node: a
     * goal already on the branch is neither announced nor written again, so forming a run's tree
     * twice — or finishing a formation that failed partway — leaves one row per node and returns
     * the stored tree. The returned tree is read back from the store, not echoed: it is what the
     * next reader will see.
     */
    suspend fun form(tree: GoalTree, runId: String): Result<GoalTree> {
        registry.registerAgent(eventApi.agentId).onFailure { return Result.failure(it) }

        val pending = ArrayDeque(listOf(Placement(tree.root, parent = null, ordinal = 0)))
        while (pending.isNotEmpty()) {
            val placement = pending.removeFirst()
            formOne(placement, runId).onFailure { return Result.failure(it) }
            placement.node.children.forEachIndexed { index, child ->
                pending.addLast(Placement(child, parent = goalRef(runId, placement.node.id), ordinal = index))
            }
        }

        return tree(runId).mapCatching { stored ->
            stored ?: error("Run '$runId' formed its goal tree and the branch holds none of it")
        }
    }

    /** The goal tree [runId] formed, as stored; `null` when the run formed none. */
    suspend fun tree(runId: String): Result<GoalTree?> =
        goals.forRun(runId).mapCatching { rows -> if (rows.isEmpty()) null else assemble(runId, rows) }

    /** Every goal [runId] formed, with its status. */
    suspend fun records(runId: String): Result<List<GoalRecord>> = goals.forRun(runId)

    /** [subject]'s open goals, oldest first — what a perception reads instead of a string goal. */
    suspend fun open(subject: Ref<Subject> = holder): Result<List<GoalRecord>> =
        goals.forSubject(subject, GoalStatus.OPEN)

    /** Mark [runId]'s goal [nodeId] complete. See [updateStatus]. */
    suspend fun complete(runId: String, nodeId: String): Result<GoalRecord> =
        updateStatus(goalRef(runId, nodeId), GoalStatus.COMPLETED)

    /**
     * Move [goal] to [status], announced first: a `GoalUpdated` through the door, then the row.
     *
     * A goal already in [status] is returned as it is, with no event — there is no change to put
     * on the record. A goal nothing formed is [UnknownGoalException].
     */
    suspend fun updateStatus(goal: Ref<Goal>, status: GoalStatus): Result<GoalRecord> {
        val current = goals.get(goal).getOrElse { return Result.failure(it) }
            ?: return Result.failure(UnknownGoalException(goal.value))
        if (current.status == status) return Result.success(current)

        val event = GoalEvent.GoalUpdated(
            eventId = generateUUID("goal-updated", eventApi.agentId),
            timestamp = eventApi.clock.now(),
            eventSource = EventSource.Agent(eventApi.agentId),
            goalId = goal.value,
            status = status.name,
            runId = current.runId,
        )
        val stored = eventApi.publish(event, runId = current.runId).getOrElse { return Result.failure(it) }
        return goals.updateStatus(goal, status, Provenance(createdBy = event.eventId, createdAt = stored.recordedAt))
            .mapCatching { it ?: throw UnknownGoalException(goal.value) }
    }

    /**
     * Record that [entity] — a work item, project or milestone the agent perceived — mirrors
     * [goal]. One `Mirrors` edge from its `environmentRef` to the goal, through the registry's door.
     *
     * Refused with [NotTrackerWorkException] for any other canon type, and with
     * [UnknownGoalException] for a goal nothing formed: an edge to a goal that is not on the
     * branch is a dangling ref the fold could never resolve.
     */
    suspend fun mirror(entity: CanonEntity, goal: Ref<Goal>): Result<Relationship<Primitive, Primitive>> {
        val from = CanonGoalMirrors.mirrorRef(entity)
            ?: return Result.failure(NotTrackerWorkException(entity.canonId, entity.canonType.name))
        goals.get(goal).getOrElse { return Result.failure(it) }
            ?: return Result.failure(UnknownGoalException(goal.value))
        return registry.relate(from, goal, RelationshipKind.Mirrors)
    }

    /** The tracker views that mirror [goal], in the order they were linked. */
    suspend fun mirrorsOf(goal: Ref<Goal>): Result<List<Ref<Environment>>> =
        relationships.to(goal, RelationshipKind.Mirrors).map { edges -> edges.map { Ref<Environment>(it.from.value) } }

    /**
     * The goals the tracker view [view] mirrors, in the order they were linked. [view] is the
     * entity's `environmentRef`, or [CanonGoalMirrors.dependencyRef] for one it depends on.
     */
    suspend fun goalsMirroredBy(view: Ref<Environment>): Result<List<Ref<Goal>>> =
        relationships.from(view).map { edges ->
            edges.filter { it.kind == RelationshipKind.Mirrors }.map { Ref<Goal>(it.to.value) }
        }

    /**
     * [item]'s `dependsOn` as a `Rule.Dependency` over the goals its dependencies mirror, with
     * the dependencies that mirror nothing listed apart. See [CanonGoalMirrors.dependency].
     */
    suspend fun dependencies(item: CanonWorkItem): Result<DependencyMapping> {
        val mirrored = mutableMapOf<Ref<Environment>, Set<Ref<Goal>>>()
        for (dependency in item.dependsOn.distinct()) {
            val view = CanonGoalMirrors.dependencyRef(item, dependency)
            mirrored[view] = goalsMirroredBy(view).getOrElse { return Result.failure(it) }.toSet()
        }
        return Result.success(CanonGoalMirrors.dependency(item) { mirrored[it].orEmpty() })
    }

    private suspend fun formOne(placement: Placement, runId: String): Result<GoalRecord> {
        val ref = goalRef(runId, placement.node.id)
        goals.get(ref).getOrElse { return Result.failure(it) }?.let { return Result.success(it) }

        val event = GoalEvent.GoalFormed(
            eventId = generateUUID("goal-formed", eventApi.agentId),
            timestamp = eventApi.clock.now(),
            eventSource = EventSource.Agent(eventApi.agentId),
            goalId = ref.value,
            parentId = placement.parent?.value,
            ordinal = placement.ordinal,
            description = placement.node.description,
            subjectId = holder.value,
            runId = runId,
        )
        val stored = eventApi.publish(event, runId = runId).getOrElse { return Result.failure(it) }
        return goals.insert(
            GoalRecord(
                goal = Goal(
                    id = ref,
                    description = placement.node.description,
                    parent = placement.parent,
                    provenance = Provenance(createdBy = event.eventId, createdAt = stored.recordedAt),
                ),
                status = GoalStatus.OPEN,
                subject = holder,
                runId = runId,
                ordinal = placement.ordinal,
            ),
        )
    }

    /** Rebuild a run's tree from its rows: one root, children by parent, siblings by ordinal. */
    private fun assemble(runId: String, rows: List<GoalRecord>): GoalTree {
        val roots = rows.filter { it.goal.parent == null }
        val root = roots.singleOrNull()
            ?: error("Run '$runId' holds ${roots.size} root goals; a goal tree has exactly one")
        val childrenOf = rows.filter { it.goal.parent != null }.groupBy { it.goal.parent!!.value }

        fun node(record: GoalRecord): GoalNode = GoalNode(
            id = nodeId(runId, record.id),
            description = record.goal.description,
            children = childrenOf[record.id.value].orEmpty().sortedBy { it.ordinal }.map { node(it) },
        )
        return GoalTree(root = node(root))
    }

    private data class Placement(val node: GoalNode, val parent: Ref<Goal>?, val ordinal: Int)

    companion object {
        /** The branch's ref for [runId]'s goal node [nodeId]: `<run id>/<node id>`. */
        fun goalRef(runId: String, nodeId: String): Ref<Goal> = Ref("$runId/$nodeId")

        /** The node id [goal] was formed with in [runId]; the inverse of [goalRef]. */
        fun nodeId(runId: String, goal: Ref<Goal>): String = goal.value.removePrefix("$runId/")

        /**
         * A branch over [database], announcing through [eventApi]: the goal store, the edge store,
         * and a Subject registry over the same door for the edges and the holder's registration.
         */
        fun over(database: Database, eventApi: AgentEventApi): GoalBranch {
            val relationships = SqlDelightRelationshipStore(database)
            return GoalBranch(
                goals = SqlDelightGoalStore(database),
                relationships = relationships,
                registry = SubjectRegistry(
                    subjects = SqlDelightSubjectStore(database),
                    relationships = relationships,
                    eventApi = eventApi,
                ),
                eventApi = eventApi,
            )
        }
    }
}

/** A goal was asked for by a ref nothing formed. */
class UnknownGoalException(
    val id: String,
) : Exception("No goal is on the branch under id '$id'")

/**
 * [GoalBranch.mirror] was handed a canon entity that is not tracker work. Only a work item, a
 * project or a milestone is a view of a goal; an email or a calendar event mirrors nothing.
 */
class NotTrackerWorkException(
    val canonId: CanonId,
    val canonType: String,
) : Exception("Canon entity '${canonId.value}' is a $canonType, which cannot mirror a goal")
