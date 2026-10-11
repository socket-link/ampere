package link.socket.ampere.subject

import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import link.socket.ampere.db.Database
import link.socket.ampere.field.Primitive
import link.socket.ampere.field.Provenance
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Relationship
import link.socket.ampere.field.RelationshipKind
import link.socket.ampere.util.ioDispatcher

/**
 * Persistence boundary for [Relationship] edges (AMPR-416, F30).
 *
 * Membership is an edge and nothing else: a `Subject.Group` has no member list, so an agent
 * joining an `AgentTeam` is a [RelationshipKind.MemberOf] row from the member to the group,
 * written once, with the provenance of the event that recorded it. The goal branch (F8) writes
 * [RelationshipKind.Mirrors] rows through the same store.
 *
 * Refs are stored as their `String` values with no foreign key to `SubjectStore`: a `Mirrors`
 * edge points at a Goal, and the fold resolves refs, not the database. The type parameters on
 * [Relationship] are phantoms, so every edge is read back as `Relationship<Primitive, Primitive>`
 * and the caller narrows by what it asked for.
 *
 * All fallible operations return [Result]; no exceptions cross this boundary.
 */
interface RelationshipStore {

    /**
     * Record one edge, or return the edge already there.
     *
     * Idempotent: the same `(from, to, kind)` is the same edge, and the first record keeps its
     * provenance. There is nothing to conflict on — an edge carries no payload beyond its key.
     */
    suspend fun record(relationship: Relationship<Primitive, Primitive>): Result<Relationship<Primitive, Primitive>>

    /** The one edge with this exact key, or `null`. */
    suspend fun get(
        from: Ref<Primitive>,
        to: Ref<Primitive>,
        kind: RelationshipKind,
    ): Result<Relationship<Primitive, Primitive>?>

    /** Every edge leaving [from], in record order. */
    suspend fun from(from: Ref<Primitive>): Result<List<Relationship<Primitive, Primitive>>>

    /** Every edge entering [to], optionally only those of [kind], in record order. */
    suspend fun to(
        to: Ref<Primitive>,
        kind: RelationshipKind? = null,
    ): Result<List<Relationship<Primitive, Primitive>>>

    /** Forget an edge. The Subjects at either end are not touched. */
    suspend fun delete(
        from: Ref<Primitive>,
        to: Ref<Primitive>,
        kind: RelationshipKind,
    ): Result<Unit>
}

/**
 * [RelationshipStore] over the `RelationshipStore` table (`RelationshipStore.sq`, `9.sqm`).
 *
 * `kind` is stored as the simple name of the [RelationshipKind] object and is *not* CHECKed in
 * the schema, because the kind is sealed rather than an enum so a later kind can carry data. A
 * row whose kind this build does not know is skipped by the list reads and reported as a failure
 * by [get], the AMPR-364 rule applied to edges.
 */
class SqlDelightRelationshipStore(
    private val database: Database,
) : RelationshipStore {

    private val queries
        get() = database.relationshipStoreQueries

    override suspend fun record(
        relationship: Relationship<Primitive, Primitive>,
    ): Result<Relationship<Primitive, Primitive>> = withContext(ioDispatcher) {
        runCatching {
            val kind = RelationshipKindCodec.encode(relationship.kind)
            queries.insertRelationship(
                from_ref = relationship.from.value,
                to_ref = relationship.to.value,
                kind = kind,
                created_by = relationship.provenance.createdBy,
                created_at = relationship.provenance.createdAt.toEpochMilliseconds(),
            )
            // OR IGNORE means the first record wins; read back so the caller holds that one.
            selectRow(relationship.from.value, relationship.to.value, kind)?.toRelationship()
                ?: error("Relationship ${relationship.from.value} -> ${relationship.to.value} vanished after insert")
        }
    }

    override suspend fun get(
        from: Ref<Primitive>,
        to: Ref<Primitive>,
        kind: RelationshipKind,
    ): Result<Relationship<Primitive, Primitive>?> = withContext(ioDispatcher) {
        runCatching { selectRow(from.value, to.value, RelationshipKindCodec.encode(kind))?.toRelationship() }
    }

    override suspend fun from(from: Ref<Primitive>): Result<List<Relationship<Primitive, Primitive>>> =
        withContext(ioDispatcher) {
            runCatching {
                queries.selectRelationshipsFrom(from.value, ::StoredRelationshipRow)
                    .executeAsList()
                    .mapNotNull { it.toRelationshipOrNull() }
            }
        }

    override suspend fun to(
        to: Ref<Primitive>,
        kind: RelationshipKind?,
    ): Result<List<Relationship<Primitive, Primitive>>> = withContext(ioDispatcher) {
        runCatching {
            val rows = if (kind == null) {
                queries.selectRelationshipsTo(to.value, ::StoredRelationshipRow).executeAsList()
            } else {
                queries.selectRelationshipsToByKind(
                    to.value,
                    RelationshipKindCodec.encode(kind),
                    ::StoredRelationshipRow,
                ).executeAsList()
            }
            rows.mapNotNull { it.toRelationshipOrNull() }
        }
    }

    override suspend fun delete(
        from: Ref<Primitive>,
        to: Ref<Primitive>,
        kind: RelationshipKind,
    ): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            queries.deleteRelationship(from.value, to.value, RelationshipKindCodec.encode(kind))
        }.map { }
    }

    private fun selectRow(from: String, to: String, kind: String): StoredRelationshipRow? =
        queries.selectRelationship(from, to, kind, ::StoredRelationshipRow).executeAsOneOrNull()
}

/**
 * The text form of a [RelationshipKind] in the `kind` column: the object's simple name.
 *
 * Hand-written rather than reflective so the stored strings are a declared contract: renaming a
 * kind in Kotlin must not silently orphan every edge recorded under the old name.
 */
object RelationshipKindCodec {

    const val MEMBER_OF = "MemberOf"
    const val MIRRORS = "Mirrors"

    fun encode(kind: RelationshipKind): String = when (kind) {
        RelationshipKind.MemberOf -> MEMBER_OF
        RelationshipKind.Mirrors -> MIRRORS
    }

    /** The kind [text] names, or `null` for a kind this build does not have. */
    fun decode(text: String): RelationshipKind? = when (text) {
        MEMBER_OF -> RelationshipKind.MemberOf
        MIRRORS -> RelationshipKind.Mirrors
        else -> null
    }
}

/** One `RelationshipStore` row as read, kind still as text so an unknown one can be skipped. */
internal data class StoredRelationshipRow(
    val fromRef: String,
    val toRef: String,
    val kind: String,
    val createdBy: String,
    val createdAt: Long,
) {
    fun toRelationshipOrNull(): Relationship<Primitive, Primitive>? {
        val decoded = RelationshipKindCodec.decode(kind) ?: return null
        return Relationship(
            from = Ref(fromRef),
            to = Ref(toRef),
            kind = decoded,
            provenance = Provenance(createdBy = createdBy, createdAt = Instant.fromEpochMilliseconds(createdAt)),
        )
    }

    fun toRelationship(): Relationship<Primitive, Primitive> =
        toRelationshipOrNull()
            ?: throw UnknownRelationshipKindException(fromRef, toRef, kind)
}

/**
 * A stored edge names a [RelationshipKind] this build does not have — version skew at the edge
 * boundary. The list reads skip such a row; a single-row [RelationshipStore.get] has no rest of
 * the query to save, so it says so.
 */
class UnknownRelationshipKindException(
    val from: String,
    val to: String,
    val kind: String,
) : Exception("Stored relationship $from -> $to has kind '$kind', which this build does not know")
