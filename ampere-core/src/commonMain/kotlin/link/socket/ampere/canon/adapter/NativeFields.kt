package link.socket.ampere.canon.adapter

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.NativeSchema

/**
 * A typed cursor over one native object's fields, used by [ReadableCanonAdapter.projectFields]
 * implementations.
 *
 * ## Why this exists
 *
 * Written by hand, every required-field read in a canon projection is four lines of the same
 * shape:
 *
 * ```
 * val title = fields["title"]?.jsonPrimitive?.contentOrNull
 *     ?: return canonFailure(CanonConversionFailure.MissingRequiredField(canonType, "title", nativeSchema))
 * ```
 *
 * At roughly eight fields per adapter across a growing Plug surface that is on the order of a
 * thousand lines of near-identical `?: return canonFailure(...)`, which is both a maintenance
 * cost and the most likely place for the failure taxonomy to drift between adapters — one
 * reporting `MalformedField` where another reports `MissingRequiredField` for the same
 * situation. Centralising the reads makes the taxonomy uniform by construction.
 *
 * ## Why it throws internally
 *
 * The canon SPI's public contract is that conversions are `Result`-typed and never throw
 * ([CanonConversionException] exists only to carry a typed failure across the `Result`
 * boundary). A reader that returned `Result` per field would force every projection back into
 * the `?: return` shape this type exists to remove, so instead the accessors throw
 * [CanonConversionException] and [project] catches it.
 *
 * **The throw never escapes.** [project] is the only way to obtain a [NativeFields], and it
 * converts the exception back to `Result.failure` — so a projection written with this type
 * satisfies the same never-throws contract as one written by hand. Callers must not construct
 * a reader outside [project]; the constructor is private for that reason.
 *
 * Nested reads return child cursors, so failures carry a path: `location.latitude`,
 * `people[1].name`.
 */
class NativeFields private constructor(
    private val fields: JsonObject,
    private val canonType: CanonType,
    private val schema: NativeSchema,
    private val path: String,
) {
    // -----------------------------------------------------------------
    // Required reads — absent or unreadable is a typed failure
    // -----------------------------------------------------------------

    fun requireString(key: String): String = optionalString(key) ?: missing(key)

    /** Reads an epoch-millisecond `Long`, the shape platform glue projects dates into. */
    fun requireEpochMillis(key: String): Instant = optionalEpochMillis(key) ?: missing(key)

    fun requireDouble(key: String): Double = optionalDouble(key) ?: missing(key)

    fun requireInt(key: String): Int = optionalInt(key) ?: missing(key)

    fun requireLong(key: String): Long = optionalLong(key) ?: missing(key)

    fun requireBoolean(key: String): Boolean = optionalBoolean(key) ?: missing(key)

    fun requireObject(key: String): NativeFields = optionalObject(key) ?: missing(key)

    // -----------------------------------------------------------------
    // Optional reads — absent is null; present-but-unreadable is still a failure
    // -----------------------------------------------------------------

    /**
     * Absent or JSON `null` yields null. A value of the wrong *kind* (an object where a
     * string was expected) is a [CanonConversionFailure.MalformedField] rather than null,
     * so a provider changing a field's shape surfaces loudly instead of silently reading
     * as "not set".
     */
    fun optionalString(key: String): String? =
        primitive(key)?.let { it.contentOrNull ?: malformed(key, "expected a string") }

    fun optionalEpochMillis(key: String): Instant? =
        primitive(key)?.let { primitive ->
            primitive.longOrNull?.let(Instant::fromEpochMilliseconds)
                ?: malformed(key, "expected an epoch-millisecond long")
        }

    fun optionalDouble(key: String): Double? =
        primitive(key)?.let { it.doubleOrNull ?: malformed(key, "expected a number") }

    fun optionalInt(key: String): Int? =
        primitive(key)?.let { it.intOrNull ?: malformed(key, "expected an integer") }

    fun optionalLong(key: String): Long? =
        primitive(key)?.let { it.longOrNull ?: malformed(key, "expected a long") }

    fun optionalBoolean(key: String): Boolean? =
        primitive(key)?.let { it.booleanOrNull ?: malformed(key, "expected a boolean") }

    fun optionalBoolean(
        key: String,
        default: Boolean,
    ): Boolean = optionalBoolean(key) ?: default

    /** Nested objects read through a child cursor, so their failures carry the full path. */
    fun optionalObject(key: String): NativeFields? =
        when (val element = fields[key]) {
            null -> null
            is JsonObject -> NativeFields(element, canonType, schema, child(key))
            else -> malformed(key, "expected an object")
        }

    /**
     * Reads a homogeneous array of objects. Absent yields an empty list; a present non-array
     * is a failure. Non-object members are skipped rather than failing the whole read — an
     * array that mixes shapes is a provider quirk, not a corrupt record.
     */
    fun objectArray(key: String): List<NativeFields> =
        when (val element = fields[key]) {
            null -> emptyList()
            is JsonArray ->
                element.mapIndexedNotNull { index, member ->
                    (member as? JsonObject)?.let {
                        NativeFields(it, canonType, schema, "${child(key)}[$index]")
                    }
                }
            else -> malformed(key, "expected an array")
        }

    fun contains(key: String): Boolean = fields.containsKey(key)

    // -----------------------------------------------------------------
    // Failure construction
    // -----------------------------------------------------------------

    /**
     * Fails the projection with a caller-supplied reason. For the cases a generic reader
     * can't express — an enum discriminator that maps to nothing, a pair of fields that
     * disagree.
     */
    fun <T> malformed(
        key: String,
        reason: String,
    ): T =
        throw CanonConversionException(
            CanonConversionFailure.MalformedField(canonType, child(key), reason),
        )

    private fun <T> missing(key: String): T =
        throw CanonConversionException(
            CanonConversionFailure.MissingRequiredField(canonType, child(key), schema),
        )

    /** Absent and JSON `null` are the same thing to a projection: the field is not set. */
    private fun primitive(key: String): JsonPrimitive? =
        when (val element = fields[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> element
            else -> malformed(key, "expected a primitive")
        }

    /** Qualifies a key with its position in the object graph, e.g. `location.latitude`. */
    private fun child(key: String): String = if (path.isEmpty()) key else "$path.$key"

    companion object {
        /**
         * Runs [block] against a cursor over [fields], converting any typed failure the
         * cursor raises back into `Result.failure`.
         *
         * This is the only entry point, so the internal throw cannot escape into caller code.
         */
        fun <E> project(
            fields: JsonObject,
            canonType: CanonType,
            schema: NativeSchema,
            block: (NativeFields) -> E,
        ): Result<E> =
            try {
                Result.success(block(NativeFields(fields, canonType, schema, path = "")))
            } catch (failure: CanonConversionException) {
                Result.failure(failure)
            }
    }
}
