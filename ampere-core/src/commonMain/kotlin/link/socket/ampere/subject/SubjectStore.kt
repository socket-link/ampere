package link.socket.ampere.subject

import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import link.socket.ampere.db.Database
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject
import link.socket.ampere.util.ioDispatcher

/**
 * The closed set of `kind` values a `SubjectStore` row can carry — one per branch of the sealed
 * [Subject] (AMPR-416, F30). The schema CHECKs the same three names.
 */
enum class SubjectKind {
    AGENT,
    HUMAN,
    GROUP,
    ;

    companion object {
        fun of(subject: Subject): SubjectKind = when (subject) {
            is Subject.Agent -> AGENT
            is Subject.Human -> HUMAN
            is Subject.Group -> GROUP
        }
    }
}

/**
 * Persistence boundary for [Subject] rows — the Subject registry (AMPR-416, F30).
 *
 * Before this store an `AgentId` was a bare String nobody could look up, the public API used a
 * team role as an agent id (C44), no team id was ever issued (C51), and the human behind
 * `EventSource.Human` was an anonymous singleton. The Field's fold cannot resolve a holder it
 * cannot look up — and the belief columns (F9) write `holder` values that resolve here.
 *
 * The store holds the *rows*; it does not announce them. Registration — the event through the
 * one door, the provenance that event supplies — is [SubjectRegistry]'s. A [Subject] handed to
 * [register] already carries its [Provenance], and the store writes it as given.
 *
 * All fallible operations return [Result]; no exceptions cross this boundary. The failures are
 * [SubjectIdInUseException] (an id registered under another kind) and [UnknownSubjectException]
 * (from the registry's resolvers).
 */
interface SubjectStore {

    /**
     * Write one Subject row, or return the row already there.
     *
     * Idempotent for the same id under the same kind: a retry of a registration that may or may
     * not have committed is the same registration, and the stored row — with the provenance of
     * the first write — is what comes back. The same id under a *different* kind is refused with
     * [SubjectIdInUseException]: an id names one Subject, and silently re-typing it would make
     * every edge and belief that points at it mean something else.
     */
    suspend fun register(subject: Subject): Result<Subject>

    /** The Subject [ref] names, or `null` when nothing is registered under it. */
    suspend fun get(ref: Ref<Subject>): Result<Subject?>

    /** Every registered Subject of [kind], in registration order; all of them when `null`. */
    suspend fun list(kind: SubjectKind? = null): Result<List<Subject>>

    /** Forget a Subject. Edges that point at it are not touched; the fold resolves refs, not the database. */
    suspend fun delete(ref: Ref<Subject>): Result<Unit>
}

/**
 * [SubjectStore] over the `SubjectStore` table (`SubjectStore.sq`, created by `9.sqm`).
 *
 * Writes commit on [ioDispatcher]. Reads decode nothing but the five scalar columns, so a row
 * this build cannot read is not reachable by version skew here — `kind` is CHECKed in the
 * schema to the three names [SubjectKind] has.
 */
class SqlDelightSubjectStore(
    private val database: Database,
) : SubjectStore {

    private val queries
        get() = database.subjectStoreQueries

    override suspend fun register(subject: Subject): Result<Subject> = withContext(ioDispatcher) {
        runCatching {
            val kind = SubjectKind.of(subject)
            val existing = selectRow(subject.id.value)
            if (existing != null) {
                if (existing.kind == kind) return@runCatching existing.toSubject()
                throw SubjectIdInUseException(subject.id.value, requested = kind, existing = existing.kind)
            }
            queries.insertSubject(
                id = subject.id.value,
                kind = kind.name,
                display_name = subject.name,
                created_by = subject.provenance.createdBy,
                created_at = subject.provenance.createdAt.toEpochMilliseconds(),
            )
            // Read back rather than echo the argument: two racing registrations of the same id
            // resolve to one row through the PRIMARY KEY, and the loser must learn which won.
            selectRow(subject.id.value)?.toSubject()
                ?: error("Subject '${subject.id.value}' vanished between insert and read-back")
        }
    }

    override suspend fun get(ref: Ref<Subject>): Result<Subject?> = withContext(ioDispatcher) {
        runCatching { selectRow(ref.value)?.toSubject() }
    }

    override suspend fun list(kind: SubjectKind?): Result<List<Subject>> = withContext(ioDispatcher) {
        runCatching {
            val rows = if (kind == null) {
                queries.selectAllSubjects(::StoredSubjectRow).executeAsList()
            } else {
                queries.selectSubjectsByKind(kind.name, ::StoredSubjectRow).executeAsList()
            }
            rows.map { it.toSubject() }
        }
    }

    override suspend fun delete(ref: Ref<Subject>): Result<Unit> = withContext(ioDispatcher) {
        runCatching { queries.deleteSubject(ref.value) }.map { }
    }

    private fun selectRow(id: String): StoredSubjectRow? =
        queries.selectSubject(id, ::StoredSubjectRow).executeAsOneOrNull()
}

/** One `SubjectStore` row as read, kind still as text so the decode failure names the row. */
internal data class StoredSubjectRow(
    val id: String,
    val kindText: String,
    val displayName: String,
    val createdBy: String,
    val createdAt: Long,
) {
    val kind: SubjectKind
        get() = SubjectKind.entries.firstOrNull { it.name == kindText }
            ?: error("Subject '$id' has kind '$kindText', which this build does not know")

    fun toSubject(): Subject {
        val provenance = Provenance(createdBy = createdBy, createdAt = Instant.fromEpochMilliseconds(createdAt))
        return when (kind) {
            SubjectKind.AGENT -> Subject.Agent(Ref(id), displayName, provenance)
            SubjectKind.HUMAN -> Subject.Human(Ref(id), displayName, provenance)
            SubjectKind.GROUP -> Subject.Group(Ref(id), displayName, provenance)
        }
    }
}

/**
 * [SubjectStore.register] was handed an id that already names a Subject of another kind.
 *
 * Re-registering the same id under the same kind succeeds and returns the existing row; what is
 * refused is re-typing. An id is the value every `Relationship` edge and every F9 `holder`
 * column points at, so changing what kind of thing it names would silently change what all of
 * them mean.
 */
class SubjectIdInUseException(
    val id: String,
    val requested: SubjectKind,
    val existing: SubjectKind,
) : Exception("Subject id '$id' is already registered as $existing, so it cannot be registered as $requested")

/**
 * A resolver was asked for a Subject nothing has registered.
 *
 * Returned by [SubjectRegistry]'s resolvers rather than swallowed, because an unresolvable
 * holder is the one failure a registry must not hide: it means either a registration never
 * committed or a producer is naming an identity from somewhere else, and both are worth a
 * person's attention.
 */
class UnknownSubjectException(
    val id: String,
) : Exception("No Subject is registered under id '$id'")
