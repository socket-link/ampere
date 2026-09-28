package link.socket.ampere.pause

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.PersistedStore
import link.socket.ampere.agents.domain.event.StoreRowUndecodableEvent
import link.socket.ampere.agents.events.UndecodableRow
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.db.Database
import link.socket.ampere.util.ioDispatcher

/**
 * Persistence boundary for [AgentPause] — the durable side of a pending human decision (AMPR-370).
 *
 * An [AgentPause] declares that a run has stopped and needs a person. Before this store existed
 * that declaration lived only in the process that raised it, which made three things impossible:
 * answering from another device, answering an hour later, and telling an expired decision from an
 * open one. A pause is persisted at raise, addressed by [AgentPause.correlationId] from then on,
 * and settles exactly once — to a person's [AgentPauseResponse.Approved] or
 * [AgentPauseResponse.Rejected], or to [AgentPauseResponse.TimedOut] when its deadline passes.
 *
 * All fallible operations return [Result]; no exceptions cross this boundary. The failures are
 * [UnknownPauseException], [PauseCorrelationIdInUseException] and [UndecodablePauseException].
 *
 * ## What resumes — AMPR-274 Contract 4 (declared interruption fate), position D4
 *
 * **This store holds the decision, never the coroutine.** Resolving a pause does not resurrect
 * the run that raised it. Per Contract 4's D4 position, resume is *a fresh perceive over durable
 * committed state, never loop resurrection*: a settled pause is committed state, and the work
 * continues when something — the same process, a later one, a different host — performs a new
 * Perceive that reads this table and finds the decision made. Nothing here starts, restarts,
 * unblocks or notifies a suspended loop, and nothing may be added that does.
 *
 * Three consequences a consumer can rely on, and should build its gate against:
 *
 * 1. **[resolve] is complete on its own.** Its success means the decision is durable. It does not
 *    mean any awaiting party has observed it, and it never waits for one. A responder is finished
 *    once `resolve` returns.
 * 2. **The raiser is free to die.** A pause is useful precisely when the process that raised it is
 *    gone. No operation here needs the raiser alive, and none needs an in-memory handle: [get],
 *    [resolve] and [expire] all work from the [PauseCorrelationId] alone, which is why that id is
 *    the whole address. (The same property `CancellationAddress` has — the address alone is
 *    sufficient after a restart.)
 * 3. **An interrupted raise fails closed.** [raise] commits before anything downstream may act on
 *    the pause, so a crash between raise and dispatch leaves a durable open decision that a later
 *    sweep can expire, rather than an unrecorded one nobody can find. A crash before the row
 *    commits leaves no pause and no side effect.
 *
 * ## Expiry is an outcome, not a silence
 *
 * A pause whose deadline passes while nobody is watching settles durably to
 * [AgentPauseResponse.TimedOut]. Two mechanisms, deliberately overlapping:
 *
 * - [expire] settles every open pause past its deadline. This is the sweep a supervisor runs, and
 *   the reconciliation pass a process runs at startup.
 * - [get] and [listOpen] settle what they find expired *before* answering, so an open record handed
 *   back by this store is never a stale one, even on a host where no sweep ever runs.
 *
 * Reads in this store therefore write, and that is deliberate: the alternative is an
 * indefinitely-open row that a later reader cannot distinguish from a live decision.
 *
 * ## An unreadable pause still settles
 *
 * Version skew at this boundary is real — adding an [EscalationChannel] variant is a documented
 * breaking change for readers — so the store is built so that the operations which keep the table
 * honest never decode a payload. [resolve] and [expire] write through the `correlation_id`,
 * `expires_at` and `settled_at` columns alone; only [get] and [listOpen] decode, and [listOpen]
 * skips what it cannot read rather than failing (AMPR-364). A pause raised by a newer build can
 * still be answered, still times out on schedule, and cannot make every other pending decision
 * unreadable.
 *
 * ## What this store is not
 *
 * It does not dispatch. Walking [AgentPause.suggestedChannels], consulting [ChannelAvailability]
 * and reaching a person is the channel selector's job (W1.5) and stays outside this boundary — the
 * store would otherwise have to know about surfaces, which [AgentPause]'s commonMain-only contract
 * exists to prevent. It also publishes no pause events of its own; the only thing it says on the
 * bus is that it had to skip an unreadable row.
 */
interface PauseStore {

    /**
     * Persist [pause] as an open decision and return the record that now exists.
     *
     * Must be called before anything acts on the pause: the durable row is what makes the decision
     * findable if the raiser dies, and a pause that was dispatched but never stored is exactly the
     * lost decision this store removes.
     *
     * Idempotent for a byte-identical re-raise of a still-open pause — a retry after a raise that
     * may or may not have committed returns the existing record rather than failing. Every other
     * collision on [AgentPause.correlationId] fails with [PauseCorrelationIdInUseException] rather
     * than overwriting; see that exception for why.
     *
     * @param raisedAt when the pause was raised, and when its clock starts:
     *   [PauseRecord.expiresAt] is `raisedAt + pause.timeoutMillis`, fixed at this call.
     */
    suspend fun raise(
        pause: AgentPause,
        raisedAt: Instant = Clock.System.now(),
    ): Result<PauseRecord>

    /**
     * The pause stored under [correlationId], or null if there is no such row.
     *
     * Settles the row to [AgentPauseResponse.TimedOut] first if it is open and [now] is at or past
     * its deadline, so the returned record is never an expired-but-open one. Fails with
     * [UndecodablePauseException] if the row exists and this build cannot read its payload — after
     * having settled it, if it was due.
     */
    suspend fun get(
        correlationId: PauseCorrelationId,
        now: Instant = Clock.System.now(),
    ): Result<PauseRecord?>

    /**
     * Every decision still awaiting an answer as of [now], soonest deadline first.
     *
     * Runs [expire] first, so nothing in the result is past its deadline. A row this build cannot
     * decode is skipped rather than failing the query (AMPR-364) and announced on the bus.
     */
    suspend fun listOpen(now: Instant = Clock.System.now()): Result<List<PauseRecord>>

    /**
     * Settle the pause [response] names, and report what the decision settled to.
     *
     * First writer wins. If the pause settled before this call — a human reply racing the expiry
     * sweep, or two responders — the returned [PauseResolution] carries the response that actually
     * landed with [PauseResolution.settledByThisCall] false. The store will not overwrite a settled
     * decision and will not pretend the caller's response won.
     *
     * Fails with [UnknownPauseException] when no row carries the response's correlation id. Does
     * not decode the stored [AgentPause], so an unreadable pause can still be answered.
     *
     * Returning successfully means the decision is durable, and nothing more — see the resume
     * semantics on [PauseStore].
     */
    suspend fun resolve(
        response: AgentPauseResponse,
        resolvedAt: Instant = Clock.System.now(),
    ): Result<PauseResolution>

    /**
     * Settle every open pause whose deadline is at or before [now] to
     * [AgentPauseResponse.TimedOut], and return the ids this call settled.
     *
     * Decodes nothing, so a pause this build cannot read still expires on time instead of staying
     * open forever. A pause a person answers in the same instant may win the race; expiry claims
     * only the rows that were still open when its update ran.
     */
    suspend fun expire(now: Instant = Clock.System.now()): Result<List<PauseCorrelationId>>

    /**
     * Forget the pause under [correlationId] entirely, settled or not.
     *
     * For retention and for tests, not for cancelling a decision — a decision that should no longer
     * be answered is [resolve]d, so the record of it survives. Succeeds when there is no such row.
     */
    suspend fun delete(correlationId: PauseCorrelationId): Result<Unit>
}

/**
 * The persistent [PauseStore], over the `PauseStore` table.
 *
 * @param eventApi Optional door. With one, every row [listOpen] had to skip is announced as a
 *   [StoreRowUndecodableEvent] — the glass-brain rule applies to a dropped row as much as to a
 *   dropped event. Without it the store still degrades rather than failing; the skip just goes
 *   unobserved. Mirrors `SqlDelightLinkStore`.
 */
class SqlDelightPauseStore(
    private val database: Database,
    private val eventApi: AgentEventApi? = null,
    private val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : PauseStore {

    private val queries
        get() = database.pauseStoreQueries

    /**
     * Rows already announced as undecodable, and the lock over it. Said once per store, then quiet:
     * the row stays undecodable until something rewrites it, so announcing per read would turn one
     * bad row into an unbounded stream of events about it.
     */
    private val reportedUndecodableRows = mutableSetOf<String>()
    private val undecodableReportLock = Mutex()

    override suspend fun raise(pause: AgentPause, raisedAt: Instant): Result<PauseRecord> =
        withContext(ioDispatcher) {
            runCatching {
                val encoded = encodePause(pause)
                val raisedAtMillis = raisedAt.toEpochMilliseconds()
                val existing = selectRow(pause.correlationId)

                if (existing != null) {
                    // A retry of a raise that already committed is the same raise; anything else
                    // would be an overwrite, and a settled row may hold a person's decision. The
                    // stored JSON is compared rather than decoded, so this path stays readable to a
                    // build that cannot decode the payload it is re-writing.
                    if (existing.settledAt == null && existing.pauseJson == encoded) {
                        return@runCatching PauseRecord(
                            pause = pause,
                            raisedAt = Instant.fromEpochMilliseconds(existing.raisedAt),
                            expiresAt = Instant.fromEpochMilliseconds(existing.expiresAt),
                        )
                    }
                    throw PauseCorrelationIdInUseException(
                        correlationId = pause.correlationId,
                        settled = existing.settledAt != null,
                    )
                }

                val expiresAtMillis = raisedAtMillis + pause.timeoutMillis
                queries.insertPause(
                    correlation_id = pause.correlationId,
                    pause_json = encoded,
                    raised_at = raisedAtMillis,
                    expires_at = expiresAtMillis,
                )

                PauseRecord(
                    pause = pause,
                    raisedAt = raisedAt,
                    expiresAt = Instant.fromEpochMilliseconds(expiresAtMillis),
                )
            }
        }

    override suspend fun get(
        correlationId: PauseCorrelationId,
        now: Instant,
    ): Result<PauseRecord?> =
        withContext(ioDispatcher) {
            runCatching {
                val row = selectRow(correlationId) ?: return@runCatching null
                val current = if (row.isExpiredAndOpen(now)) {
                    timeOut(correlationId, now)
                    selectRow(correlationId) ?: return@runCatching null
                } else {
                    row
                }
                decodeOrFail(current)
            }
        }

    override suspend fun listOpen(now: Instant): Result<List<PauseRecord>> {
        expire(now).onFailure { throwable -> return Result.failure(throwable) }

        val skipped = mutableListOf<UndecodableRow>()

        val records = withContext(ioDispatcher) {
            runCatching {
                queries.selectOpenPauses(::StoredPauseRow).executeAsList().mapNotNull { row ->
                    try {
                        decode(row)
                    } catch (throwable: Throwable) {
                        skipped += UndecodableRow(row.correlationId, throwable.reasonText())
                        null
                    }
                }
            }
        }.getOrElse { throwable -> return Result.failure(throwable) }

        skipped.forEach { row -> announceUndecodable(row) }

        return Result.success(records)
    }

    override suspend fun resolve(
        response: AgentPauseResponse,
        resolvedAt: Instant,
    ): Result<PauseResolution> =
        withContext(ioDispatcher) {
            runCatching {
                val correlationId = response.correlationId
                val before = selectRow(correlationId) ?: throw UnknownPauseException(correlationId)

                // Something settled it already: report that answer rather than this one, and leave
                // the row alone. `settlePause` would no-op anyway — this is what makes the no-op
                // legible to the caller.
                if (before.settledAt != null) return@runCatching before.asResolution(byThisCall = false)

                val encoded = encodeResponse(response)
                val resolvedAtMillis = resolvedAt.toEpochMilliseconds()
                queries.settlePause(
                    response_json = encoded,
                    settled_at = resolvedAtMillis,
                    correlation_id = correlationId,
                )

                // Re-read rather than assume: the statement is guarded by `settled_at IS NULL`, and
                // JDBC SQLite gives no transaction isolation to lean on instead (AMPR-336), so
                // "did I win" is answered by looking.
                val after = selectRow(correlationId) ?: throw UnknownPauseException(correlationId)
                after.asResolution(
                    byThisCall = after.responseJson == encoded && after.settledAt == resolvedAtMillis,
                )
            }
        }

    override suspend fun expire(now: Instant): Result<List<PauseCorrelationId>> =
        withContext(ioDispatcher) {
            runCatching {
                queries.selectExpiredOpenIds(now.toEpochMilliseconds())
                    .executeAsList()
                    .filter { correlationId -> timeOut(correlationId, now) }
            }
        }

    override suspend fun delete(correlationId: PauseCorrelationId): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching { queries.deletePause(correlationId) }.map { }
        }

    /**
     * Settle one row to [AgentPauseResponse.TimedOut] and report whether this call is what settled
     * it. Decodes nothing: [AgentPauseResponse.TimedOut] needs the correlation id and nothing else,
     * which is why that id has its own column, and the check compares stored JSON rather than
     * parsing it.
     */
    private fun timeOut(correlationId: PauseCorrelationId, now: Instant): Boolean {
        val encoded = encodeResponse(AgentPauseResponse.TimedOut(correlationId))
        val nowMillis = now.toEpochMilliseconds()
        queries.settlePause(
            response_json = encoded,
            settled_at = nowMillis,
            correlation_id = correlationId,
        )
        val after = selectRow(correlationId) ?: return false
        return after.responseJson == encoded && after.settledAt == nowMillis
    }

    private fun selectRow(correlationId: PauseCorrelationId): StoredPauseRow? =
        queries.selectPause(correlationId, ::StoredPauseRow).executeAsOneOrNull()

    /**
     * Say on the bus that a row was skipped, once per row id. No door, no event — and no throw
     * either: a store with no observer still degrades rather than failing. The row is marked
     * reported only once the publish succeeded, so a write the door refused leaves it unreported and
     * the next read says it again.
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
                store = PersistedStore.PAUSE_STORE,
                rowId = row.rowId,
                reason = row.reason,
            ),
        ).onSuccess {
            undecodableReportLock.withLock { reportedUndecodableRows += row.rowId }
        }
    }

    /**
     * Decode one row for a caller that named it, or throw [UndecodablePauseException] for the
     * enclosing `runCatching` to turn into a typed [Result.failure]. A single-row read has no rest
     * of the query to save, so the caller gets told rather than handed a silent null.
     */
    private fun decodeOrFail(row: StoredPauseRow): PauseRecord =
        try {
            decode(row)
        } catch (throwable: Throwable) {
            throw UndecodablePauseException(row.correlationId, throwable.reasonText(), throwable)
        }

    private fun decode(row: StoredPauseRow): PauseRecord = PauseRecord(
        pause = decodePause(row.pauseJson),
        raisedAt = Instant.fromEpochMilliseconds(row.raisedAt),
        expiresAt = Instant.fromEpochMilliseconds(row.expiresAt),
        response = row.responseJson?.let(::decodeResponse),
        settledAt = row.settledAt?.let(Instant::fromEpochMilliseconds),
    )

    /**
     * The resolution a settled row describes. Only the response is decoded — never the pause — so
     * this is reachable for a row whose payload this build cannot read.
     */
    private fun StoredPauseRow.asResolution(byThisCall: Boolean): PauseResolution = PauseResolution(
        correlationId = correlationId,
        response = decodeResponse(
            checkNotNull(responseJson) { "settled row '$correlationId' has no response" },
        ),
        settledAt = Instant.fromEpochMilliseconds(
            checkNotNull(settledAt) { "settled row '$correlationId' has no settled_at" },
        ),
        settledByThisCall = byThisCall,
    )

    private fun Throwable.reasonText(): String = message ?: this::class.simpleName.orEmpty()

    private fun encodePause(pause: AgentPause): String =
        json.encodeToString(AgentPause.serializer(), pause)

    private fun decodePause(payload: String): AgentPause =
        json.decodeFromString(AgentPause.serializer(), payload)

    private fun encodeResponse(response: AgentPauseResponse): String =
        json.encodeToString(AgentPauseResponse.serializer(), response)

    private fun decodeResponse(payload: String): AgentPauseResponse =
        json.decodeFromString(AgentPauseResponse.serializer(), payload)

    /**
     * One `PauseStore` row exactly as the queries read it.
     *
     * The scalar columns are held alongside the payload rather than taken from it, so a row this
     * build cannot decode can still be named, still be expired, and still be settled.
     */
    private class StoredPauseRow(
        val correlationId: String,
        val pauseJson: String,
        val raisedAt: Long,
        val expiresAt: Long,
        val responseJson: String?,
        val settledAt: Long?,
    ) {
        fun isExpiredAndOpen(now: Instant): Boolean =
            settledAt == null && expiresAt <= now.toEpochMilliseconds()
    }
}

/**
 * In-memory [PauseStore] for tests and single-process environments.
 *
 * Mirrors `InMemoryLinkStore`: the persistent implementation is the real one, this exists so
 * callers can be exercised without a driver. It reproduces the settle-once and expire-on-read
 * behaviour so a consumer's tests mean the same thing here as against SQL. What it cannot reproduce
 * is durability, which is the one property worth testing against [SqlDelightPauseStore].
 */
class InMemoryPauseStore(
    records: List<PauseRecord> = emptyList(),
) : PauseStore {

    private val rows = records.associateBy { it.correlationId }.toMutableMap()
    private val lock = Mutex()

    override suspend fun raise(pause: AgentPause, raisedAt: Instant): Result<PauseRecord> =
        lock.withLock {
            runCatching {
                val existing = rows[pause.correlationId]
                if (existing != null) {
                    if (existing.isOpen && existing.pause == pause) return@runCatching existing
                    throw PauseCorrelationIdInUseException(
                        correlationId = pause.correlationId,
                        settled = !existing.isOpen,
                    )
                }

                val record = PauseRecord(
                    pause = pause,
                    raisedAt = raisedAt,
                    expiresAt = Instant.fromEpochMilliseconds(
                        raisedAt.toEpochMilliseconds() + pause.timeoutMillis,
                    ),
                )
                rows[pause.correlationId] = record
                record
            }
        }

    override suspend fun get(
        correlationId: PauseCorrelationId,
        now: Instant,
    ): Result<PauseRecord?> = lock.withLock {
        Result.success(timeOutIfExpired(correlationId, now))
    }

    override suspend fun listOpen(now: Instant): Result<List<PauseRecord>> = lock.withLock {
        rows.keys.toList().forEach { correlationId -> timeOutIfExpired(correlationId, now) }
        Result.success(rows.values.filter { it.isOpen }.sortedWith(OPEN_ORDER))
    }

    override suspend fun resolve(
        response: AgentPauseResponse,
        resolvedAt: Instant,
    ): Result<PauseResolution> = lock.withLock {
        runCatching {
            val existing = rows[response.correlationId]
                ?: throw UnknownPauseException(response.correlationId)

            if (!existing.isOpen) {
                return@runCatching existing.asResolution(byThisCall = false)
            }

            val settled = existing.copy(response = response, settledAt = resolvedAt)
            rows[response.correlationId] = settled
            settled.asResolution(byThisCall = true)
        }
    }

    override suspend fun expire(now: Instant): Result<List<PauseCorrelationId>> = lock.withLock {
        val due = rows.values
            .filter { it.isOpen && it.expiresAt <= now }
            .sortedWith(OPEN_ORDER)
            .map { it.correlationId }

        due.forEach { correlationId -> timeOutIfExpired(correlationId, now) }
        Result.success(due)
    }

    override suspend fun delete(correlationId: PauseCorrelationId): Result<Unit> = lock.withLock {
        rows.remove(correlationId)
        Result.success(Unit)
    }

    private fun timeOutIfExpired(correlationId: PauseCorrelationId, now: Instant): PauseRecord? {
        val record = rows[correlationId] ?: return null
        if (!record.isOpen || record.expiresAt > now) return record

        val settled = record.copy(
            response = AgentPauseResponse.TimedOut(correlationId),
            settledAt = now,
        )
        rows[correlationId] = settled
        return settled
    }

    private fun PauseRecord.asResolution(byThisCall: Boolean): PauseResolution = PauseResolution(
        correlationId = correlationId,
        response = checkNotNull(response) { "settled record '$correlationId' has no response" },
        settledAt = checkNotNull(settledAt) { "settled record '$correlationId' has no settledAt" },
        settledByThisCall = byThisCall,
    )

    private companion object {
        /** The order `selectOpenPauses` reads in: soonest deadline first, id as the tiebreak. */
        val OPEN_ORDER: Comparator<PauseRecord> =
            compareBy<PauseRecord>({ it.expiresAt }, { it.correlationId })
    }
}
