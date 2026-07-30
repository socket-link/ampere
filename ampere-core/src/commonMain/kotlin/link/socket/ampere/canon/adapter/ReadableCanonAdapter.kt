package link.socket.ampere.canon.adapter

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.canon.CanonEntity
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.SourceHandle

/**
 * The transport-agnostic read contract between a canon type and one native source.
 *
 * One adapter shape serves every wire: an AppFunction call, an MCP tool, a
 * bespoke iOS integration, a Shortcuts/URI route, a direct API. Arc logic never
 * learns which one carried the data — that is the Link's business, not the
 * canon's.
 *
 * A Plug that only reads subclasses this directly and gets no write surface at
 * all — no `WriteRejected` stub to author, no `ownedFields` to declare and
 * never honour. [WritableCanonAdapter] adds a guarded write-back path on top
 * of this; [CreatingCanonAdapter] adds creation on top of that.
 */
abstract class ReadableCanonAdapter<E : CanonEntity> {

    /** The canon type this adapter projects to. */
    abstract val canonType: CanonType

    /**
     * The native shape identifier this adapter reads, e.g. `MailMessageEntity`.
     * Checked on every projection.
     */
    abstract val nativeSchema: String

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
     * Re-read the native object by handle. [WritableCanonAdapter] calls this to
     * get a base to merge onto when an entity carries no native payload of its
     * own.
     */
    protected abstract suspend fun fetchNative(handle: SourceHandle): Result<NativePayload>

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
}
