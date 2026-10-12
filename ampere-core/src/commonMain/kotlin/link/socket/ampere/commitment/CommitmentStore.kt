package link.socket.ampere.commitment

import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import link.socket.ampere.db.Database
import link.socket.ampere.field.Commitment
import link.socket.ampere.field.Goal
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.util.ioDispatcher

/**
 * Persistence boundary for Commitments (AMPR-449, F31).
 *
 * The store holds rows. Deciding when one forms or resolves is [CommitmentLedger]'s job. A
 * commitment is identified by the event that formed it ([Provenance.createdBy]), because one
 * `TicketAssigned` forms at most one.
 *
 * All fallible operations return [Result]; no exceptions cross this boundary.
 */
interface CommitmentStore {

    /**
     * Write one commitment, or return the one already formed by the same event.
     *
     * Idempotent: folding the same `TicketAssigned` again is the same commitment, and the first
     * write keeps its status and provenance.
     */
    suspend fun insert(commitment: Commitment): Result<Commitment>

    /** The commitment [createdBy] formed, or `null`. */
    suspend fun get(createdBy: String): Result<Commitment?>

    /** Every commitment on [ticketId], open and resolved, oldest first. */
    suspend fun forTicket(ticketId: String): Result<List<Commitment>>

    /** [ticketId]'s commitments in [status], oldest first. */
    suspend fun forTicket(ticketId: String, status: Commitment.Status): Result<List<Commitment>>

    /** Every commitment in [status], soonest due first. */
    suspend fun withStatus(status: Commitment.Status): Result<List<Commitment>>

    /**
     * Resolve the open commitment [createdBy] formed, recording [resolution] as the event that
     * did it. A commitment already resolved keeps its first resolution. `null` when no commitment
     * has that id.
     */
    suspend fun resolve(createdBy: String, resolution: Provenance): Result<Commitment?>
}

/**
 * [CommitmentStore] over the `CommitmentStore` table (`CommitmentStore.sq`, created by `13.sqm`).
 *
 * Writes commit on [ioDispatcher]. Refs are stored as the strings they wrap, with no foreign key:
 * the fold resolves refs, not the database.
 */
class SqlDelightCommitmentStore(
    private val database: Database,
) : CommitmentStore {

    private val queries
        get() = database.commitmentStoreQueries

    override suspend fun insert(commitment: Commitment): Result<Commitment> = withContext(ioDispatcher) {
        runCatching {
            queries.insertCommitment(
                created_by_event = commitment.provenance.createdBy,
                ticket_id = commitment.ticketId,
                committer_ref = commitment.committer.value,
                goal_ref = commitment.goal?.value,
                due_at = commitment.due.toEpochMilliseconds(),
                status = commitment.status.name,
                created_at = commitment.provenance.createdAt.toEpochMilliseconds(),
                resolved_by_event = commitment.resolvedBy,
                resolved_at = commitment.resolvedAt?.toEpochMilliseconds(),
            )
            // OR IGNORE means the first write wins; read back so the caller holds that one.
            selectRow(commitment.provenance.createdBy)?.toCommitment()
                ?: error("Commitment '${commitment.provenance.createdBy}' vanished between insert and read-back")
        }
    }

    override suspend fun get(createdBy: String): Result<Commitment?> = withContext(ioDispatcher) {
        runCatching { selectRow(createdBy)?.toCommitment() }
    }

    override suspend fun forTicket(ticketId: String): Result<List<Commitment>> = withContext(ioDispatcher) {
        runCatching {
            queries.selectCommitmentsByTicket(ticketId, ::StoredCommitmentRow)
                .executeAsList()
                .map { it.toCommitment() }
        }
    }

    override suspend fun forTicket(
        ticketId: String,
        status: Commitment.Status,
    ): Result<List<Commitment>> = withContext(ioDispatcher) {
        runCatching {
            queries.selectCommitmentsByTicketAndStatus(ticketId, status.name, ::StoredCommitmentRow)
                .executeAsList()
                .map { it.toCommitment() }
        }
    }

    override suspend fun withStatus(status: Commitment.Status): Result<List<Commitment>> = withContext(ioDispatcher) {
        runCatching {
            queries.selectCommitmentsByStatus(status.name, ::StoredCommitmentRow)
                .executeAsList()
                .map { it.toCommitment() }
        }
    }

    override suspend fun resolve(
        createdBy: String,
        resolution: Provenance,
    ): Result<Commitment?> = withContext(ioDispatcher) {
        runCatching {
            queries.resolveCommitment(
                resolved_by_event = resolution.createdBy,
                resolved_at = resolution.createdAt.toEpochMilliseconds(),
                created_by_event = createdBy,
            )
            selectRow(createdBy)?.toCommitment()
        }
    }

    private fun selectRow(createdBy: String): StoredCommitmentRow? =
        queries.selectCommitment(createdBy, ::StoredCommitmentRow).executeAsOneOrNull()
}

/** One `CommitmentStore` row as read, status still as text so a decode failure names the row. */
internal data class StoredCommitmentRow(
    val createdBy: String,
    val ticketId: String,
    val committerRef: String,
    val goalRef: String?,
    val dueAt: Long,
    val statusText: String,
    val createdAt: Long,
    val resolvedBy: String?,
    val resolvedAt: Long?,
) {
    fun toCommitment(): Commitment {
        val status = Commitment.Status.entries.firstOrNull { it.name == statusText }
            ?: throw UnknownCommitmentStatusException(createdBy, statusText)
        return Commitment(
            committer = Ref(committerRef),
            ticketId = ticketId,
            goal = goalRef?.let { Ref<Goal>(it) },
            due = Instant.fromEpochMilliseconds(dueAt),
            status = status,
            provenance = Provenance(createdBy = createdBy, createdAt = Instant.fromEpochMilliseconds(createdAt)),
            resolvedBy = resolvedBy,
            resolvedAt = resolvedAt?.let { Instant.fromEpochMilliseconds(it) },
        )
    }
}

/**
 * A stored commitment names a [Commitment.Status] this build does not have: version skew at the
 * commitment boundary. Reported rather than skipped. A commitment silently read as some other
 * status is a lie about what a Subject has undertaken.
 */
class UnknownCommitmentStatusException(
    val createdBy: String,
    val status: String,
) : Exception("Stored commitment '$createdBy' has status '$status', which this build does not know")
