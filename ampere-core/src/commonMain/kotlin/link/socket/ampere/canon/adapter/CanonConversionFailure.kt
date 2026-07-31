package link.socket.ampere.canon.adapter

import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.NativeSchema

/**
 * Why a canon projection or write-back could not complete.
 *
 * The list is closed; callers can rely on `when` being exhaustive.
 */
sealed interface CanonConversionFailure {

    /**
     * The native payload is not the shape this adapter handles.
     *
     * Caught before any write, because merging fields from one native schema
     * into another is how a projection silently corrupts a record.
     */
    data class SchemaMismatch(
        val canonType: CanonType,
        val expectedSchema: NativeSchema,
        val actualSchema: NativeSchema,
    ) : CanonConversionFailure

    /** A field the canon type requires was absent from the native payload. */
    data class MissingRequiredField(
        val canonType: CanonType,
        val field: String,
        val schema: NativeSchema,
    ) : CanonConversionFailure

    /** A field was present but could not be read as the canon type expects. */
    data class MalformedField(
        val canonType: CanonType,
        val field: String,
        val reason: String,
    ) : CanonConversionFailure

    /**
     * The adapter tried to write a native field it does not own.
     *
     * This is the guard that keeps preserve-and-merge honest: an adapter whose
     * `canonFields` reaches outside its declared `ownedFields` is widening its
     * write footprint past what the canon projection can account for, which is
     * how "merge" degrades back into "clobber".
     */
    data class UnownedFieldWrite(
        val canonType: CanonType,
        val field: String,
        val ownedFields: Set<String>,
    ) : CanonConversionFailure

    /**
     * The native object could not be resolved for merging — the entity carried
     * no native payload and the re-fetch failed.
     */
    data class SourceUnavailable(
        val canonType: CanonType,
        val nativeId: String,
        val reason: String,
    ) : CanonConversionFailure

    /** The transport rejected the merged write. */
    data class WriteRejected(
        val canonType: CanonType,
        val nativeId: String,
        val reason: String,
    ) : CanonConversionFailure
}

/**
 * Carries a [CanonConversionFailure] through [Result.failure].
 *
 * Conversions are Result-typed; this exception exists so the typed failure
 * survives the `kotlin.Result` boundary the rest of the repo uses, not so that
 * anyone throws it.
 */
class CanonConversionException(
    val failure: CanonConversionFailure,
) : Exception("Canon conversion failed: $failure")

/** Shorthand for the Result-typed failure path. */
fun <T> canonFailure(failure: CanonConversionFailure): Result<T> =
    Result.failure(CanonConversionException(failure))
