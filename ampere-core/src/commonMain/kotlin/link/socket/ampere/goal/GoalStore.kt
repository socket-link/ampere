package link.socket.ampere.goal

import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import link.socket.ampere.db.Database
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject
import link.socket.ampere.util.ioDispatcher

/**
 * Where a goal stands (AMPR-419, F8).
 *
 * Stored by name and *not* CHECKed in the schema, so a later status is a new value here rather
 * than a table rebuild. A row whose status this build does not know is reported by name — see
 * [UnknownGoalStatusException] — rather than read as some other status.
 *
 * No weight, rank or priority lives here or anywhere on a goal: how much a goal pulls is F28,
 * open, and derived at read time when it is settled.
 */
enum class GoalStatus {
    /** Formed and not yet met. */
    OPEN,

    /** Met — Flow judged the work toward it done. */
    COMPLETED,

    /**
     * Given up: the run that formed it ended without meeting it (AMPR-473). A state, not a
     * judgment of the run — a failed, partial, stopped or cancelled run all leave their goal here.
     */
    ABANDONED,
}

/**
 * One goal on the branch, as the store holds it: the Field's [Goal] plus what only the row knows.
 *
 * @property goal The primitive — id, description, parent, and the provenance of the event that
 *   formed it.
 * @property status Where the goal stands.
 * @property subject Whose goal it is: the Subject that formed it.
 * @property runId The Arc run that formed it, or null for a goal no run formed.
 * @property ordinal The goal's place among its siblings — the order Flow works them in.
 * @property lastUpdate The event of the row's latest write: [Goal.provenance] until a status
 *   change, then the `GoalUpdated` that made it.
 */
data class GoalRecord(
    val goal: Goal,
    val status: GoalStatus,
    val subject: Ref<Subject>,
    val runId: String?,
    val ordinal: Int,
    val lastUpdate: Provenance = goal.provenance,
) {
    val id: Ref<Goal>
        get() = goal.id
}

/**
 * Persistence boundary for the Goal branch (AMPR-419, F8): the agent's own goals.
 *
 * A tracker's view of a goal is not stored here. `CanonWorkItem`, `CanonProject` and
 * `CanonMilestone` are perceived Environment, and each links to the goal it mirrors through a
 * `Mirrors` edge in the `RelationshipStore`. The store holds rows; announcing them — the event
 * through the one door, the provenance it supplies — is [GoalBranch]'s.
 *
 * All fallible operations return [Result]; no exceptions cross this boundary.
 */
interface GoalStore {

    /**
     * Write one goal, or return the goal already there.
     *
     * Idempotent: a goal's id names one goal, and the first write keeps its description,
     * status and provenance. Forming it again is the same goal, not an edit.
     */
    suspend fun insert(record: GoalRecord): Result<GoalRecord>

    /** The goal [id] names, or `null`. */
    suspend fun get(id: Ref<Goal>): Result<GoalRecord?>

    /** Every goal [runId] formed, ordered by sibling position. */
    suspend fun forRun(runId: String): Result<List<GoalRecord>>

    /** The goals directly under [parent], in sibling order. */
    suspend fun children(parent: Ref<Goal>): Result<List<GoalRecord>>

    /** [subject]'s goals in [status], oldest first. */
    suspend fun forSubject(subject: Ref<Subject>, status: GoalStatus): Result<List<GoalRecord>>

    /**
     * Move [id] to [status], recording [provenance] as the row's latest write. `null` when no
     * goal has that id — there is nothing to move.
     */
    suspend fun updateStatus(id: Ref<Goal>, status: GoalStatus, provenance: Provenance): Result<GoalRecord?>
}

/**
 * [GoalStore] over the `GoalStore` table (`GoalStore.sq`, created by `11.sqm`).
 *
 * Writes commit on [ioDispatcher]. Refs are stored as the strings they wrap, with no foreign key:
 * the fold resolves refs, not the database.
 */
class SqlDelightGoalStore(
    private val database: Database,
) : GoalStore {

    private val queries
        get() = database.goalStoreQueries

    override suspend fun insert(record: GoalRecord): Result<GoalRecord> = withContext(ioDispatcher) {
        runCatching {
            queries.insertGoal(
                id = record.id.value,
                parent_id = record.goal.parent?.value,
                ordinal = record.ordinal.toLong(),
                description = record.goal.description,
                status = record.status.name,
                subject_ref = record.subject.value,
                run_id = record.runId,
                created_by_event = record.goal.provenance.createdBy,
                created_at = record.goal.provenance.createdAt.toEpochMilliseconds(),
                updated_by_event = record.lastUpdate.createdBy,
                updated_at = record.lastUpdate.createdAt.toEpochMilliseconds(),
            )
            // OR IGNORE means the first write wins; read back so the caller holds that one.
            selectRow(record.id.value)?.toRecord()
                ?: error("Goal '${record.id.value}' vanished between insert and read-back")
        }
    }

    override suspend fun get(id: Ref<Goal>): Result<GoalRecord?> = withContext(ioDispatcher) {
        runCatching { selectRow(id.value)?.toRecord() }
    }

    override suspend fun forRun(runId: String): Result<List<GoalRecord>> = withContext(ioDispatcher) {
        runCatching {
            queries.selectGoalsByRun(runId, ::StoredGoalRow).executeAsList().map { it.toRecord() }
        }
    }

    override suspend fun children(parent: Ref<Goal>): Result<List<GoalRecord>> = withContext(ioDispatcher) {
        runCatching {
            queries.selectChildGoals(parent.value, ::StoredGoalRow).executeAsList().map { it.toRecord() }
        }
    }

    override suspend fun forSubject(
        subject: Ref<Subject>,
        status: GoalStatus,
    ): Result<List<GoalRecord>> = withContext(ioDispatcher) {
        runCatching {
            queries.selectGoalsBySubjectAndStatus(subject.value, status.name, ::StoredGoalRow)
                .executeAsList()
                .map { it.toRecord() }
        }
    }

    override suspend fun updateStatus(
        id: Ref<Goal>,
        status: GoalStatus,
        provenance: Provenance,
    ): Result<GoalRecord?> = withContext(ioDispatcher) {
        runCatching {
            queries.updateGoalStatus(
                status = status.name,
                updated_by_event = provenance.createdBy,
                updated_at = provenance.createdAt.toEpochMilliseconds(),
                id = id.value,
            )
            selectRow(id.value)?.toRecord()
        }
    }

    private fun selectRow(id: String): StoredGoalRow? =
        queries.selectGoal(id, ::StoredGoalRow).executeAsOneOrNull()
}

/** One `GoalStore` row as read, status still as text so a decode failure names the row. */
internal data class StoredGoalRow(
    val id: String,
    val parentId: String?,
    val ordinal: Long,
    val description: String,
    val statusText: String,
    val subjectRef: String,
    val runId: String?,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
) {
    fun toRecord(): GoalRecord {
        val status = GoalStatus.entries.firstOrNull { it.name == statusText }
            ?: throw UnknownGoalStatusException(id, statusText)
        return GoalRecord(
            goal = Goal(
                id = Ref(id),
                description = description,
                parent = parentId?.let { Ref<Goal>(it) },
                provenance = Provenance(createdBy = createdBy, createdAt = Instant.fromEpochMilliseconds(createdAt)),
            ),
            status = status,
            subject = Ref(subjectRef),
            runId = runId,
            ordinal = ordinal.toInt(),
            lastUpdate = Provenance(createdBy = updatedBy, createdAt = Instant.fromEpochMilliseconds(updatedAt)),
        )
    }
}

/**
 * A stored goal names a [GoalStatus] this build does not have — version skew at the goal
 * boundary. Reported rather than skipped: a tree read with one node missing is a different tree,
 * and a goal silently read as some other status is a lie about the agent's intent.
 */
class UnknownGoalStatusException(
    val id: String,
    val status: String,
) : Exception("Stored goal '$id' has status '$status', which this build does not know")
