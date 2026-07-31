package link.socket.ampere.bindings.apple

import kotlinx.serialization.Serializable

/**
 * A [link.socket.ampere.canon.CanonType]'s projection onto Apple's assistant-schema
 * vocabulary.
 *
 * Bindings are *data*, not annotations. Kotlin annotations cannot be read
 * reflectively on Kotlin/Native and `kotlin-reflect` is JVM-only, so an
 * annotation-based binding would be unavailable on exactly the platform that
 * needs it most. Modelling bindings as values also makes them serializable, so
 * a trace records which projection produced an entity.
 *
 * The canon type is the superset; a binding is a *projection* of it. Everything
 * the projection drops is named in [lossyFields] and is what the
 * preserve-and-merge write-back contract
 * ([link.socket.ampere.canon.adapter.WritableCanonAdapter]) exists to protect.
 */
@Serializable
data class AppleCanonBinding(
    val schema: AppleSchemaBinding? = null,
    val lossyFields: List<String> = emptyList(),
) {
    companion object {
        /** A canon type with no Apple binding at all — Ring 3 service types. */
        val UNBOUND: AppleCanonBinding = AppleCanonBinding(schema = null)
    }
}
