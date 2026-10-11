package link.socket.ampere.agents.domain.emission.kinds

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.domain.emission.EmissionKind
import link.socket.ampere.agents.domain.emission.EmissionPayload

/**
 * Descriptor for a domain [EmissionKind]: a typed Emission kind defined
 * outside `EmissionKind`'s sealed hierarchy, in any package or module.
 *
 * A descriptor binds a lowercase slug [id] to a [serializer] for its body
 * type [T], a [schemaVersion] for that body, and a [fallbackText] projection
 * for text-only surfaces. It is the only thing a producer and a consumer
 * need to share: the producer calls [encode] and publishes the result under
 * [kind]; the consumer calls [decodeOrNull] on whatever payload arrives.
 *
 * ```kotlin
 * val RecipeKind = DomainEmissionKind("recipe", 1, RecipePayload.serializer()) { it.title }
 *
 * // producer
 * emission(...) { present(RecipeKind, carbonara) }
 *
 * // consumer
 * RecipeKind.decodeOrNull(event.emission.payload)?.let { render(it) }
 * ```
 *
 * The body travels through the bus, the event log and the digest as a
 * [EmissionPayload.Domain] — plain data to everything in between — so no
 * `Json` anywhere needs a `SerializersModule` for the kind, and a consumer
 * that does not know the kind still decodes the event and can show
 * [EmissionPayload.Domain.fallbackText].
 *
 * Versioning: bump [schemaVersion] only for a change an older descriptor
 * cannot read. An additive field with a default is not such a change —
 * [decodeOrNull] applies defaults and ignores unknown keys, so same-version
 * and older bodies both decode.
 *
 * @param id the kind's lowercase slug (`recipe`, `meal-plan`). Validated.
 * @param schemaVersion the version of [T]'s wire shape this descriptor
 *   writes and the newest it reads. Must be `>= 1`.
 * @param serializer how [T] is written into and read out of the body.
 * @param fallbackText what text-only surfaces show for a value. Must return
 *   non-blank prose, never JSON.
 */
class DomainEmissionKind<T>(
    val id: String,
    val schemaVersion: Int,
    val serializer: KSerializer<T>,
    val fallbackText: (T) -> String,
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion must be >= 1, got $schemaVersion" }
    }

    /** The [EmissionKind] an Emission of this kind carries. */
    val kind: EmissionKind.Domain = EmissionKind.Domain(id)

    /**
     * Encode [value] as the payload for an Emission of this kind. The body is
     * written with one canonical [Json], so equal values produce byte-equal
     * bodies and a stable `inputDigest`.
     */
    fun encode(value: T): EmissionPayload.Domain = EmissionPayload.Domain(
        kindId = id,
        schemaVersion = schemaVersion,
        body = CanonicalJson.encodeToJsonElement(serializer, value),
        fallbackText = fallbackText(value),
    )

    /**
     * Decode [payload] as a [T], or `null` when it is not one this descriptor
     * can read: a non-[EmissionPayload.Domain] payload, a different `kindId`,
     * a `schemaVersion` newer than this descriptor's, or a body that does not
     * fit [serializer]. Same-or-older versions decode leniently: unknown keys
     * are ignored and missing defaulted fields take their defaults.
     *
     * A `null` is the signal to fall back — to skip the Emission or to show
     * its `fallbackText` — never a reason to fail the surrounding event.
     */
    fun decodeOrNull(payload: EmissionPayload): T? {
        val domain = payload as? EmissionPayload.Domain ?: return null
        if (domain.kindId != id || domain.schemaVersion > schemaVersion) return null
        return try {
            CanonicalJson.decodeFromJsonElement(serializer, domain.body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            // A body that decodes but fails the type's own `init` validation.
            null
        }
    }

    override fun toString(): String = "DomainEmissionKind(id=$id, schemaVersion=$schemaVersion)"

    private companion object {
        /**
         * The one [Json] every descriptor encodes and decodes with. Encoding
         * writes defaults so a body is complete and its digest deterministic;
         * decoding ignores unknown keys so a newer same-version body (an
         * additive field) still reads.
         */
        val CanonicalJson: Json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            ignoreUnknownKeys = true
            prettyPrint = false
        }
    }
}
