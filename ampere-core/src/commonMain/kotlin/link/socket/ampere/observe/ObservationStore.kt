package link.socket.ampere.observe

import kotlin.time.Duration
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import link.socket.ampere.db.Database
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
}

/**
 * [ObservationStore] over the `ObservationStore` table (`ObservationStore.sq`, created by
 * `12.sqm`).
 *
 * Runs on [ioDispatcher]. Refs are stored as the strings they wrap, with no foreign key: the
 * fold resolves refs, not the database.
 */
class SqlDelightObservationStore(
    private val database: Database,
) : ObservationStore {

    private val queries
        get() = database.observationStoreQueries

    override suspend fun insert(observations: List<Belief.Settled>): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            database.transactionWithResult {
                observations.count { observation ->
                    val key = observation.provenance.createdBy
                    val present = queries.observationExists(key, observation.subject.value, observation.holder.value)
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
        }
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
