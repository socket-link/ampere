package link.socket.ampere.canon.adapter

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.canon.CanonEntity
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.SourceHandle

/**
 * The transport-agnostic contract between a canon type and one native source.
 *
 * One adapter shape serves every wire: an AppFunction call, an MCP tool, a
 * bespoke iOS integration, a Shortcuts/URI route, a direct API. Arc logic never
 * learns which one carried the data — that is the Link's business, not the
 * canon's.
 *
 * ## Preserve-and-merge is structural, not remembered
 *
 * A canon projection is lossy by design, so a naive write-back is destructive:
 * writing a projected entity back to its source clobbers every native field the
 * projection dropped. The usual fix — "adapters must remember to merge" — fails
 * the first time someone writes an adapter in a hurry.
 *
 * So this SPI does not expose a write path that *can* clobber. Subclasses
 * implement three narrow operations:
 *
 *  - [projectFields] — native fields in, canon entity out.
 *  - [canonFields] — canon entity in, *only the fields this adapter owns* out.
 *  - [writeNative] — a terminal write of an already-merged payload.
 *
 * [writeBack] is the only write entry point, it is final, and it always routes
 * through [mergeForWriteBack]: resolve the native object, overlay the canon
 * deltas onto it, hand the merged result to [writeNative]. An adapter author
 * cannot write an unmerged entity without deleting a member of this class.
 *
 * The [ownedFields] declaration is the second half of that guarantee. If
 * [canonFields] returns a key outside it, the merge fails with
 * [CanonConversionFailure.UnownedFieldWrite] rather than quietly overwriting a
 * native field nobody accounted for.
 */
abstract class CanonAdapter<E : CanonEntity> {

    /** The canon type this adapter projects to. */
    abstract val canonType: CanonType

    /**
     * The native shape identifier this adapter reads and writes, e.g.
     * `MailMessageEntity`. Checked on every projection and every merge.
     */
    abstract val nativeSchema: String

    /**
     * Exactly the native field names this adapter's canon projection covers.
     *
     * Everything outside this set is preserved verbatim through write-back.
     * Under-declaring is safe (the field is simply never written);
     * over-declaring is not, because it re-opens the clobber path.
     */
    abstract val ownedFields: Set<String>

    /**
     * Native → canon. The provenance is built for you and cannot be omitted.
     *
     * @param fields The native object's fields, already schema-checked.
     * @param provenance Carries the source handle, the observation time, and
     *   the lossless native payload when the caller asked to retain it.
     */
    protected abstract fun projectFields(
        fields: JsonObject,
        provenance: CanonProvenance,
    ): Result<E>

    /**
     * Canon → native deltas. Return only the fields named in [ownedFields].
     *
     * This is a *delta*, not a document: omit a key and the native value
     * survives write-back untouched.
     */
    protected abstract fun canonFields(entity: E): Result<Map<String, JsonElement>>

    /**
     * Re-read the native object. Called only when the entity carries no native
     * payload of its own, so the merge still has a base to overlay onto.
     */
    protected abstract suspend fun fetchNative(handle: SourceHandle): Result<NativePayload>

    /** Terminal write. The payload handed in is already merged. */
    protected abstract suspend fun writeNative(
        handle: SourceHandle,
        merged: NativePayload,
    ): Result<Unit>

    /**
     * Native → canon, with the schema check and provenance wiring applied.
     *
     * @param carryNativePayload Retain the lossless native object on the
     *   entity. Default on: it makes write-back a pure merge with no re-fetch.
     *   Turn it off for payloads too large to carry, at the cost of a
     *   [fetchNative] round trip on write.
     */
    fun project(
        payload: NativePayload,
        handle: SourceHandle,
        observedAt: Instant,
        carryNativePayload: Boolean = true,
    ): Result<E> {
        if (payload.schema != nativeSchema) {
            return canonFailure(
                CanonConversionFailure.SchemaMismatch(
                    canonType = canonType,
                    expectedSchema = nativeSchema,
                    actualSchema = payload.schema,
                ),
            )
        }

        return projectFields(
            fields = payload.fields,
            provenance = CanonProvenance(
                sourceHandle = handle,
                observedAt = observedAt,
                nativePayload = if (carryNativePayload) payload else null,
            ),
        )
    }

    /**
     * Produce the payload [writeBack] would write, without writing it.
     *
     * Exposed so callers can inspect or diff a pending write — and so tests can
     * assert field preservation without a live transport.
     */
    suspend fun mergeForWriteBack(entity: E): Result<NativePayload> {
        val handle = entity.provenance.sourceHandle

        val base = entity.provenance.nativePayload
            ?: fetchNative(handle).getOrElse { error ->
                return canonFailure(
                    CanonConversionFailure.SourceUnavailable(
                        canonType = canonType,
                        nativeId = handle.nativeId,
                        reason = error.message ?: error::class.simpleName.orEmpty(),
                    ),
                )
            }

        if (base.schema != nativeSchema) {
            return canonFailure(
                CanonConversionFailure.SchemaMismatch(
                    canonType = canonType,
                    expectedSchema = nativeSchema,
                    actualSchema = base.schema,
                ),
            )
        }

        val deltas = canonFields(entity).getOrElse { return Result.failure(it) }

        deltas.keys.firstOrNull { it !in ownedFields }?.let { stray ->
            return canonFailure(
                CanonConversionFailure.UnownedFieldWrite(
                    canonType = canonType,
                    field = stray,
                    ownedFields = ownedFields,
                ),
            )
        }

        // The overlay is the whole preserve-and-merge guarantee: every native
        // key the projection dropped is still in `base.fields` and survives.
        return Result.success(
            NativePayload(
                schema = base.schema,
                fields = JsonObject(base.fields + deltas),
            ),
        )
    }

    /**
     * The only write path. Merges, then writes.
     *
     * Final by Kotlin default — overriding it would be the one way to
     * reintroduce the destructive write this SPI exists to prevent.
     */
    suspend fun writeBack(entity: E): Result<Unit> =
        mergeForWriteBack(entity).fold(
            onSuccess = { merged -> writeNative(entity.provenance.sourceHandle, merged) },
            onFailure = { Result.failure(it) },
        )
}
