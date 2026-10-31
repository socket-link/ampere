package link.socket.ampere.bindings.android

import link.socket.ampere.canon.CanonType

/**
 * Every [CanonType]'s projection onto Android's AppFunctions vocabulary,
 * settled by the AMPR-510 pass against [VERIFIED_AGAINST] and recorded in
 * `docs/ampr-222-domain-type-canon-v1.md`.
 *
 * Every entry is [AndroidSchemaBinding.None]. The beta01 artifacts declare the
 * `@AppFunctionSchemaDefinition` annotation but ship no interface carrying it:
 * not in `appfunctions`, not in `appfunctions-compiler`, not in
 * `appfunctions-testing`, and no `appfunctions-schema*` artifact is published.
 * A predefined schema is something an agent developer publishes as their own
 * SDK, so Android has no noun catalogue for any canon type to bind to.
 *
 * Because the verdict is "the artifact ships none", not a per-type judgement,
 * the map still derives from [CanonType.entries] and a new canon type needs no
 * edit here. What does need a re-run is a new artifact version: when
 * [VERIFIED_AGAINST] moves, enumerate again, and flip an entry to
 * [AndroidSchemaBinding.Predefined] only for a schema that ships.
 */
object AndroidCanonBindingRegistry {

    /** The artifact the bindings below were enumerated from. */
    const val VERIFIED_AGAINST: String = "androidx.appfunctions:appfunctions:1.0.0-beta01"

    private val bindings: Map<CanonType, AndroidCanonBinding> =
        CanonType.entries.associateWith { AndroidCanonBinding(schema = AndroidSchemaBinding.None) }

    /** The Android binding for [type]. */
    fun bindingFor(type: CanonType): AndroidCanonBinding = bindings.getValue(type)
}
