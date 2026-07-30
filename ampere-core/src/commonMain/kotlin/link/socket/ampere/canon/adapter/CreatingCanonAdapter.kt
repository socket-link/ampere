package link.socket.ampere.canon.adapter

import kotlinx.serialization.json.JsonObject
import link.socket.ampere.canon.CanonEntity
import link.socket.ampere.canon.SourceHandle

/**
 * Adds creation on top of [WritableCanonAdapter].
 *
 * [WritableCanonAdapter.writeBack] resolves `entity.provenance.sourceHandle`
 * and merges onto an existing native object. A new reminder, alarm, or pass
 * has no prior object and no handle — [create] is the separate path for that.
 * It never reads the entity's provenance, only [canonFields]: pass any [E]
 * instance that satisfies the type (its provenance is ignored) and use the
 * [SourceHandle] [create] returns to [ReadableCanonAdapter.project] the entity
 * that actually carries real provenance.
 *
 * Creation touches no existing native object, so there is nothing for it to
 * clobber — the failure mode [WritableCanonAdapter.writeBack] guards against
 * cannot occur here. That is why this is a separate, final entry point rather
 * than an overload of `writeBack`, not a relaxation of it: the [ownedFields]
 * guard still applies, because an adapter that can create with unowned fields
 * can create a malformed native object.
 */
abstract class CreatingCanonAdapter<E : CanonEntity> : WritableCanonAdapter<E>() {

    /** Terminal create. The fields handed in already passed the [ownedFields] guard. */
    protected abstract suspend fun createNative(fields: JsonObject): Result<SourceHandle>

    /**
     * The only creation path. Guards, then creates.
     *
     * Final by Kotlin default — overriding it would be the one way to
     * reintroduce an unguarded write, this time on creation rather than
     * update.
     */
    suspend fun create(entity: E): Result<SourceHandle> {
        val fields = canonFields(entity).getOrElse { return Result.failure(it) }

        fields.keys.firstOrNull { it !in ownedFields }?.let { stray ->
            return canonFailure(
                CanonConversionFailure.UnownedFieldWrite(
                    canonType = canonType,
                    field = stray,
                    ownedFields = ownedFields,
                ),
            )
        }

        return createNative(JsonObject(fields))
    }
}
