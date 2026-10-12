package link.socket.ampere.propel

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.PersistedStore
import link.socket.ampere.agents.domain.event.StoreRowUndecodableEvent
import link.socket.ampere.agents.events.UndecodableRow
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.db.Database
import link.socket.ampere.util.ioDispatcher

/**
 * Persistence boundary for [HeldRun] — the durable record of a run that stopped waiting on a
 * person (AMPR-430, AMPR-385 row H30).
 *
 * One record per run id. A run that holds is recorded here; whoever resumes it [claim]s the
 * record, and exactly one caller ever gets it; the record's owner [delete]s it once the run is
 * done with it. Nothing in Ampere writes or reads this store yet — the run that holds and the
 * resume that carries on are the next ticket's.
 *
 * All fallible operations return [Result]; no exceptions cross this boundary. The failures are
 * [HeldRunInUseException] and [UndecodableHeldRunException].
 *
 * ## Bookkeeping never decodes a payload
 *
 * [list], the claim's guard and [delete] work from the scalar columns beside the payload, so a
 * record a later or earlier build cannot read is still listed and still deletable (AMPR-364, as
 * `PauseStore` applies it). Only [get] and [claim] decode, and [claim] decodes *before* it claims:
 * a row this build cannot read is left unclaimed for a build that can, rather than taken up by a
 * caller that could do nothing with it.
 *
 * ## First writer wins, in the statement
 *
 * A claim is one `UPDATE … WHERE claimed_at IS NULL`, and whether it landed is read from the
 * statement's affected-row count. JDBC SQLite gives no transaction isolation to lean on instead
 * (AMPR-336).
 *
 * ## What this store is not
 *
 * It has no clock of its own: a hold's deadline belongs to whoever owns the decision, which
 * [HeldRun.reference] points at. It does not recover a claimed record whose resume was
 * interrupted — that record stays listed, with its [HeldRunSummary.claimedAt], for its owner. And
 * it is not `PauseStore`: that store holds a decision, this one holds a run's state.
 */
interface HeldRunStore {

    /**
     * Record [run] under its [HeldRun.runId].
     *
     * - No record for the run → stored.
     * - An unclaimed record → succeeds without writing if it is identical to [run] (a retry by a
     *   writer that did not learn whether its write landed); a different one fails with
     *   [HeldRunInUseException].
     * - A claimed record → replaced, and the claim cleared: a resumed run that has held again.
     */
    suspend fun hold(run: HeldRun): Result<Unit>

    /**
     * The record stored under [runId], or null if there is none. Fails with
     * [UndecodableHeldRunException] if the row exists and this build cannot read it.
     */
    suspend fun get(runId: RunId): Result<HeldRun?>

    /** Every record, claimed or not, oldest [HeldRun.heldAt] first. Decodes nothing. */
    suspend fun list(): Result<List<HeldRunSummary>>

    /**
     * Take the run up. Of any number of concurrent callers exactly one gets
     * [HeldRunClaim.Claimed]; the rest get [HeldRunClaim.AlreadyClaimed] with when it was taken.
     * [HeldRunClaim.NotHeld] when there is no record.
     *
     * Fails with [UndecodableHeldRunException], and claims nothing, when the row exists and this
     * build cannot read it.
     */
    suspend fun claim(
        runId: RunId,
        now: Instant = Clock.System.now(),
    ): Result<HeldRunClaim>

    /** Forget the record under [runId], claimed or not. Succeeds when there is none. */
    suspend fun delete(runId: RunId): Result<Unit>
}

/**
 * The persistent [HeldRunStore], over the `HeldRunStore` table.
 *
 * @param eventApi Optional door. With one, every row [get] or [claim] could not decode is
 *   announced, once, as a [StoreRowUndecodableEvent]. Without it the store still degrades rather
 *   than failing; the skip just goes unobserved. Mirrors `SqlDelightPauseStore`.
 */
class SqlDelightHeldRunStore(
    private val database: Database,
    private val eventApi: AgentEventApi? = null,
    private val json: Json = DEFAULT_JSON,
) : HeldRunStore {

    private val queries
        get() = database.heldRunStoreQueries

    /**
     * Rows already announced as undecodable, and the lock over it. Said once per store, then quiet:
     * the row stays undecodable until something rewrites it.
     */
    private val reportedUndecodableRows = mutableSetOf<String>()
    private val undecodableReportLock = Mutex()

    override suspend fun hold(run: HeldRun): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val encoded = encode(run)
                val heldAtMillis = run.heldAt.toEpochMilliseconds()

                // Each pass either writes or learns that another writer moved the row between the
                // read and the write, and then reads again. The stored JSON is compared rather than
                // decoded, so a row this build cannot read is still refused or replaced correctly.
                while (true) {
                    val existing = selectRow(run.runId)
                    val written = when {
                        existing == null -> queries.insertHeldRun(
                            run_id = run.runId,
                            held_run_json = encoded,
                            held_step_id = run.heldStepId,
                            reason = run.reason,
                            reference = run.reference,
                            held_at = heldAtMillis,
                        ).value
                        existing.claimedAt == null -> {
                            if (existing.heldRunJson == encoded) return@runCatching
                            throw HeldRunInUseException(run.runId)
                        }
                        else -> queries.replaceClaimedHeldRun(
                            held_run_json = encoded,
                            held_step_id = run.heldStepId,
                            reason = run.reason,
                            reference = run.reference,
                            held_at = heldAtMillis,
                            run_id = run.runId,
                        ).value
                    }
                    if (written > 0L) return@runCatching
                }
            }
        }

    override suspend fun get(runId: RunId): Result<HeldRun?> {
        val row = withContext(ioDispatcher) { runCatching { selectRow(runId) } }
            .getOrElse { throwable -> return Result.failure(throwable) }
            ?: return Result.success(null)
        return decodeOrFail(row)
    }

    override suspend fun list(): Result<List<HeldRunSummary>> =
        withContext(ioDispatcher) {
            runCatching {
                queries.selectHeldRunSummaries { runId, heldStepId, reason, reference, heldAt, claimedAt ->
                    HeldRunSummary(
                        runId = runId,
                        heldStepId = heldStepId,
                        reason = reason,
                        reference = reference,
                        heldAt = Instant.fromEpochMilliseconds(heldAt),
                        claimedAt = claimedAt?.let(Instant::fromEpochMilliseconds),
                    )
                }.executeAsList()
            }
        }

    override suspend fun claim(runId: RunId, now: Instant): Result<HeldRunClaim> {
        val nowMillis = now.toEpochMilliseconds()

        // Decode first, claim second: a row this build cannot read is left unclaimed. A pass that
        // claims nothing re-reads, because the row it decoded may have been claimed, deleted, or —
        // claimed, resumed and held again — replaced since; the payload guard on the update is
        // what tells the last of those from a lost race.
        while (true) {
            val row = withContext(ioDispatcher) { runCatching { selectRow(runId) } }
                .getOrElse { throwable -> return Result.failure(throwable) }
                ?: return Result.success(HeldRunClaim.NotHeld)

            row.claimedAt?.let { claimedAt ->
                return Result.success(HeldRunClaim.AlreadyClaimed(Instant.fromEpochMilliseconds(claimedAt)))
            }

            val run = decodeOrFail(row).getOrElse { throwable -> return Result.failure(throwable) }

            val claimed = withContext(ioDispatcher) {
                runCatching {
                    queries.claimHeldRun(
                        claimed_at = nowMillis,
                        run_id = runId,
                        held_run_json = row.heldRunJson,
                    ).value
                }
            }.getOrElse { throwable -> return Result.failure(throwable) }

            if (claimed > 0L) return Result.success(HeldRunClaim.Claimed(run))
        }
    }

    override suspend fun delete(runId: RunId): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching { queries.deleteHeldRun(runId) }.map { }
        }

    private fun selectRow(runId: RunId): StoredHeldRunRow? =
        queries.selectHeldRun(runId, ::StoredHeldRunRow).executeAsOneOrNull()

    /**
     * Decode one row for a caller that named it, or fail with [UndecodableHeldRunException] after
     * announcing the row. A single-row read has no rest of the query to save, so the caller is told
     * rather than handed a silent null.
     */
    private suspend fun decodeOrFail(row: StoredHeldRunRow): Result<HeldRun> =
        try {
            Result.success(decode(row.heldRunJson))
        } catch (throwable: Throwable) {
            val reason = throwable.message ?: throwable::class.simpleName.orEmpty()
            announceUndecodable(UndecodableRow(row.runId, reason))
            Result.failure(UndecodableHeldRunException(row.runId, reason, throwable))
        }

    /**
     * Say on the bus that a row could not be read, once per row id. No door, no event — and no
     * throw either. The row is marked reported only once the publish succeeded, so a write the door
     * refused leaves it unreported and the next read says it again.
     */
    private suspend fun announceUndecodable(row: UndecodableRow) {
        val api = eventApi ?: return
        val reported = undecodableReportLock.withLock { row.rowId in reportedUndecodableRows }
        if (reported) return

        api.publish(
            StoreRowUndecodableEvent(
                eventId = generateUUID("store-row-undecodable", api.agentId),
                timestamp = api.clock.now(),
                eventSource = EventSource.Agent(api.agentId),
                store = PersistedStore.HELD_RUN_STORE,
                rowId = row.rowId,
                reason = row.reason,
            ),
        ).onSuccess {
            undecodableReportLock.withLock { reportedUndecodableRows += row.rowId }
        }
    }

    private fun encode(run: HeldRun): String =
        json.encodeToString(HeldRun.serializer(), run)

    private fun decode(payload: String): HeldRun =
        json.decodeFromString(HeldRun.serializer(), payload)

    /**
     * One `HeldRunStore` row exactly as the queries read it. The scalar columns are held alongside
     * the payload rather than taken from it, so a row this build cannot decode can still be named,
     * listed and deleted.
     */
    private class StoredHeldRunRow(
        val runId: String,
        val heldRunJson: String,
        val heldStepId: String,
        val reason: String,
        val reference: String?,
        val heldAt: Long,
        val claimedAt: Long?,
    )

    internal companion object {
        /** What the store encodes with unless told otherwise — the `PauseStore` configuration. */
        val DEFAULT_JSON: Json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/**
 * In-memory [HeldRunStore] for tests and single-process environments.
 *
 * Reproduces the hold, claim-once and list rules so a caller's tests mean the same thing here as
 * against SQL. What it cannot reproduce is durability — the whole reason the store exists — or an
 * undecodable row, since it never encodes one.
 */
class InMemoryHeldRunStore : HeldRunStore {

    private val rows = mutableMapOf<RunId, Entry>()
    private val lock = Mutex()

    override suspend fun hold(run: HeldRun): Result<Unit> = lock.withLock {
        runCatching {
            val existing = rows[run.runId]
            if (existing != null && existing.claimedAt == null) {
                if (existing.run == run) return@runCatching
                throw HeldRunInUseException(run.runId)
            }
            rows[run.runId] = Entry(run, claimedAt = null)
        }
    }

    override suspend fun get(runId: RunId): Result<HeldRun?> = lock.withLock {
        Result.success(rows[runId]?.run)
    }

    override suspend fun list(): Result<List<HeldRunSummary>> = lock.withLock {
        Result.success(
            rows.values
                .sortedWith(compareBy<Entry>({ it.run.heldAt.toMillisPrecision() }, { it.run.runId }))
                .map { entry ->
                    HeldRunSummary(
                        runId = entry.run.runId,
                        heldStepId = entry.run.heldStepId,
                        reason = entry.run.reason,
                        reference = entry.run.reference,
                        heldAt = entry.run.heldAt.toMillisPrecision(),
                        claimedAt = entry.claimedAt,
                    )
                },
        )
    }

    override suspend fun claim(runId: RunId, now: Instant): Result<HeldRunClaim> = lock.withLock {
        val entry = rows[runId] ?: return@withLock Result.success(HeldRunClaim.NotHeld)
        entry.claimedAt?.let { claimedAt ->
            return@withLock Result.success(HeldRunClaim.AlreadyClaimed(claimedAt))
        }
        rows[runId] = entry.copy(claimedAt = now.toMillisPrecision())
        Result.success(HeldRunClaim.Claimed(entry.run))
    }

    override suspend fun delete(runId: RunId): Result<Unit> = lock.withLock {
        rows.remove(runId)
        Result.success(Unit)
    }

    private data class Entry(val run: HeldRun, val claimedAt: Instant?)

    /**
     * The SQL store keeps `held_at` and `claimed_at` as epoch milliseconds, so the times it reports
     * from those columns are truncated to them; this store reports the same instants.
     */
    private fun Instant.toMillisPrecision(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())
}
