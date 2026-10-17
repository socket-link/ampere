package link.socket.ampere.observe

import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.db.Database
import link.socket.ampere.db.field.ObservationStorePruning
import link.socket.ampere.field.Belief
import link.socket.ampere.field.BeliefKind
import link.socket.ampere.field.Decay
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.util.ioDispatcher

/*
 * Package `link.socket.ampere.observe` — where Observations are kept (AMPR-447, F7).
 *
 * `field.observe` maps a perceived canon entity to an Observation and stores nothing; this is the
 * store that mapping's output goes into. It cannot sit under `field/`: the leaf rule forbids the
 * Field primitives from importing the database.
 */

/**
 * Persistence boundary for the Field's Environment branch: the Observations each Subject holds
 * about the canon entities it perceived.
 *
 * Kept apart from knowledge on purpose (AMPR-328 Q7). A perceived entity is not a lesson, and an
 * Observation stored as knowledge would surface in every recall. Each row is the fold of the
 * `CanonPerceivedEvent` its provenance names; no other event announces it.
 *
 * Every read is scoped (F12): a viewer sees PUBLIC rows and its own, and the replay read never
 * returns a PROTECTED one — which, by default, is every Observation of a person.
 *
 * The table cannot grow without bound (AMPR-465): every write enforces [ObservationBudget], and
 * [forgetSubject] / [forgetHolder] are the sanctioned way to remove rows, so a consumer's forget
 * path never writes raw SQL against the table.
 *
 * All fallible operations return [Result]; no exceptions cross this boundary.
 */
interface ObservationStore {

    /**
     * Write [observations] in one transaction, and return how many were not already there.
     *
     * Idempotent: an Observation is keyed by its perceive event, what it is about and who holds
     * it, so storing the same perceive again writes nothing and returns `0`. The first write
     * stands.
     */
    suspend fun insert(observations: List<Belief.Settled>): Result<Int>

    /**
     * What [viewer] may see, newest first, at most [limit]: every PUBLIC Observation plus the
     * ones [viewer] holds. A `null` viewer sees PUBLIC ones only. The bound applies to rows the
     * viewer may see.
     */
    suspend fun visibleTo(viewer: Ref<Subject>?, limit: Int): Result<List<Belief.Settled>>

    /**
     * The replay read of one perceive: the Observations folding [perceiveEventId] produced, for
     * no viewer. PRIVATE rows are included; PROTECTED rows never are.
     */
    suspend fun replayable(perceiveEventId: String): Result<List<Belief.Settled>>

    /**
     * Forget everything observed about [subject], for every holder, and return how many rows
     * went. A consumer's forget path for one entity; never raw SQL.
     */
    suspend fun forgetSubject(subject: Ref<*>): Result<Int>

    /**
     * Forget everything [holder] observed, and return how many rows went. A consumer's forget
     * path for one perceiving Subject; never raw SQL.
     */
    suspend fun forgetHolder(holder: Ref<Subject>): Result<Int>
}

/**
 * [ObservationStore] over the `ObservationStore` table (`ObservationStore.sq`, created by
 * `12.sqm`), with its retention marker in `ObservationStorePruning` (`16.sqm`).
 *
 * Runs on [ioDispatcher]. Refs are stored as the strings they wrap, with no foreign key: the
 * fold resolves refs, not the database.
 *
 * [ObservationBudget] is enforced here, on the way in (AMPR-465), the way `EventRepository.saveEvent`
 * enforces `EventStoreBudget`: write-side, so a process that never perceives never pays for it
 * and a process that does cannot outrun it. Every [sweepIntervalInserts] rows written — and on
 * the first insert of each store, so an already-oversized database is reclaimed before it grows
 * further — a sweep drops rows older than [maxAge], rows whose [Decay.ExpiresAt] has passed,
 * and, only when the table is over [maxRows], the oldest rows down to [pruneToRows]. A sweep
 * that dropped anything leaves one marker row; nothing is dropped silently. Time comes from
 * [clock], never `Clock.System` inline, so tests can pin it.
 *
 * @param clock the time source every sweep measures age and expiry against.
 * @param maxAge age past which a row is dropped; see [ObservationBudget.MAX_AGE].
 * @param maxRows high-water row count; see [ObservationBudget.MAX_ROWS].
 * @param pruneToRows low-water count a cap pass prunes down to; see [ObservationBudget.PRUNE_TO_ROWS].
 * @param sweepIntervalInserts rows written between sweeps; see [ObservationBudget.SWEEP_INTERVAL_INSERTS].
 */
class SqlDelightObservationStore(
    private val database: Database,
    private val clock: Clock = Clock.System,
    private val maxAge: Duration = ObservationBudget.MAX_AGE,
    private val maxRows: Long = ObservationBudget.MAX_ROWS,
    private val pruneToRows: Long = ObservationBudget.PRUNE_TO_ROWS,
    private val sweepIntervalInserts: Long = ObservationBudget.SWEEP_INTERVAL_INSERTS,
) : ObservationStore {

    init {
        require(pruneToRows <= maxRows) {
            "pruneToRows ($pruneToRows) must not exceed maxRows ($maxRows)"
        }
    }

    private val queries
        get() = database.observationStoreQueries

    private val pruningQueries
        get() = database.observationStorePruningQueries

    /**
     * Serializes the insert counter and the sweep, so two writers cannot sweep concurrently and
     * delete rows out from under each other's accounting.
     */
    private val sweepLock = Mutex()

    /**
     * Rows written since the last budget sweep, and the reason the first insert of every store
     * sweeps: it starts at the interval. Read and written only under [sweepLock].
     */
    private var insertsSinceSweep: Long = sweepIntervalInserts

    override suspend fun insert(observations: List<Belief.Settled>): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            sweepLock.withLock {
                val added = database.transactionWithResult {
                    observations.count { observation ->
                        val key = observation.provenance.createdBy
                        val present = queries
                            .observationExists(key, observation.subject.value, observation.holder.value)
                            .executeAsOne() > 0
                        if (!present) {
                            queries.insertObservation(
                                holder = observation.holder.value,
                                subject = observation.subject.value,
                                content = observation.content,
                                confidence = observation.confidence,
                                sensitivity = observation.sensitivity.name,
                                decay = ObservationDecay.encode(observation.decay),
                                created_by_event = key,
                                created_at = observation.provenance.createdAt.toEpochMilliseconds(),
                            )
                        }
                        !present
                    }
                }
                // After the insert's transaction has committed, so a sweep failure cannot undo a
                // write, and counted even for an empty list: the first call is the first chance
                // to reclaim a database that was already over budget when it was opened.
                insertsSinceSweep += added
                if (insertsSinceSweep >= sweepIntervalInserts) {
                    insertsSinceSweep = 0
                    sweepToBudget(now = clock.now())
                }
                added
            }
        }
    }

    /**
     * One retention pass, in one transaction: the age pass, the expiry pass, then the cap pass
     * only if the table is over [maxRows]. Writes one marker row when anything was dropped, and
     * nothing when nothing was.
     *
     * Dropping is oldest-first by `created_at` and never by sensitivity: a PROTECTED row is
     * kept no longer and no shorter than a PRIVATE one.
     */
    private fun sweepToBudget(now: Instant) {
        database.transaction {
            val nowMs = now.toEpochMilliseconds()
            val rowsBefore = queries.countObservations().executeAsOne()

            val droppedByAge = queries.deleteObservationsOlderThan((now - maxAge).toEpochMilliseconds()).value
            val droppedByExpiry = queries.deleteObservationsExpiredBy(nowMs).value
            val remaining = rowsBefore - droppedByAge - droppedByExpiry
            val droppedByCap = if (remaining > maxRows) {
                queries.deleteOldestObservations(remaining - pruneToRows).value
            } else {
                0L
            }

            val dropped = droppedByAge + droppedByExpiry + droppedByCap
            if (dropped == 0L) return@transaction

            pruningQueries.insertPruning(
                pruned_at = nowMs,
                dropped_by_age = droppedByAge,
                dropped_by_expiry = droppedByExpiry,
                dropped_by_cap = droppedByCap,
                rows_before = rowsBefore,
                rows_after = rowsBefore - dropped,
            )
            pruningQueries.trimPruningRecords(ObservationBudget.MAX_PRUNING_RECORDS)
        }
    }

    /**
     * Every retention pass that dropped something from this database, newest first (AMPR-465).
     * A reader that finds no row for something a holder perceived last quarter reads this to
     * tell "never stored" from "swept out, and when".
     */
    suspend fun getPruningRecords(): Result<List<ObservationStorePruning>> = withContext(ioDispatcher) {
        runCatching { pruningQueries.getPruningRecords().executeAsList() }
    }

    override suspend fun forgetSubject(subject: Ref<*>): Result<Int> = withContext(ioDispatcher) {
        runCatching { queries.deleteObservationsBySubject(subject.value).value.toInt() }
    }

    override suspend fun forgetHolder(holder: Ref<Subject>): Result<Int> = withContext(ioDispatcher) {
        runCatching { queries.deleteObservationsByHolder(holder.value).value.toInt() }
    }

    override suspend fun visibleTo(
        viewer: Ref<Subject>?,
        limit: Int,
    ): Result<List<Belief.Settled>> = withContext(ioDispatcher) {
        runCatching {
            // SQLite reads a negative LIMIT as no limit at all.
            require(limit >= 0) { "limit must not be negative, was $limit" }
            queries.selectObservationsVisibleTo(viewer?.value, limit.toLong(), ::StoredObservationRow)
                .executeAsList()
                .map { it.toObservation() }
        }
    }

    override suspend fun replayable(perceiveEventId: String): Result<List<Belief.Settled>> =
        withContext(ioDispatcher) {
            runCatching {
                queries.selectReplayableObservationsByEvent(perceiveEventId, ::StoredObservationRow)
                    .executeAsList()
                    .map { it.toObservation() }
            }
        }
}

/**
 * How a [Decay] is kept in the `decay` column: `NONE`, `HALF_LIFE:<ISO-8601 duration>` or
 * `EXPIRES_AT:<epoch ms>`. Text rather than columns per variant, so a later [Decay] is a new
 * prefix here and not a table rebuild.
 */
internal object ObservationDecay {

    private const val NONE = "NONE"
    private const val HALF_LIFE = "HALF_LIFE:"
    private const val EXPIRES_AT = "EXPIRES_AT:"

    fun encode(decay: Decay): String = when (decay) {
        Decay.None -> NONE
        is Decay.HalfLife -> HALF_LIFE + decay.halfLife.toIsoString()
        is Decay.ExpiresAt -> EXPIRES_AT + decay.at.toEpochMilliseconds()
    }

    /** The [Decay] [text] encodes, or `null` when this build cannot read it. */
    fun decode(text: String): Decay? = when {
        text == NONE -> Decay.None
        text.startsWith(HALF_LIFE) ->
            Duration.parseIsoStringOrNull(text.removePrefix(HALF_LIFE))?.let { Decay.HalfLife(it) }
        text.startsWith(EXPIRES_AT) ->
            text.removePrefix(EXPIRES_AT).toLongOrNull()?.let { Decay.ExpiresAt(Instant.fromEpochMilliseconds(it)) }
        else -> null
    }
}

/** One `ObservationStore` row as read, enums still as text so a decode failure names the row. */
internal data class StoredObservationRow(
    val holder: String,
    val subject: String,
    val content: String,
    val confidence: Double,
    val sensitivityText: String,
    val decayText: String,
    val createdBy: String,
    val createdAt: Long,
) {
    fun toObservation(): Belief.Settled {
        val sensitivity = Sensitivity.entries.firstOrNull { it.name == sensitivityText }
            ?: throw UndecodableObservationException(createdBy, subject, holder, "sensitivity", sensitivityText)
        val decay = ObservationDecay.decode(decayText)
            ?: throw UndecodableObservationException(createdBy, subject, holder, "decay", decayText)
        return Belief.Settled(
            holder = Ref(holder),
            subject = Ref(subject),
            content = content,
            // The table holds Observations only (F7), so the kind is the table's, not a column.
            kind = BeliefKind.OBSERVATION,
            confidence = confidence,
            sensitivity = sensitivity,
            decay = decay,
            provenance = Provenance(createdBy = createdBy, createdAt = Instant.fromEpochMilliseconds(createdAt)),
        )
    }
}

/**
 * A stored Observation holds a sensitivity or decay this build cannot read — version skew at the
 * Environment boundary. Reported rather than skipped or guessed: an Observation read with the
 * wrong sensitivity is a visibility decision made by accident.
 */
class UndecodableObservationException(
    val perceiveEventId: String,
    val subject: String,
    val holder: String,
    val column: String,
    val value: String,
) : Exception(
    "Stored observation ($perceiveEventId, $subject, $holder) has $column '$value', which this build cannot read",
)
