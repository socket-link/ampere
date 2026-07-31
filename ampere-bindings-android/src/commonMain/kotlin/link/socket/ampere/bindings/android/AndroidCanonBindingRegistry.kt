package link.socket.ampere.bindings.android

import link.socket.ampere.canon.CanonType

/**
 * Every [CanonType]'s projection onto Android's AppFunctions vocabulary.
 *
 * Every entry is [AndroidSchemaBinding.PendingSdkVerification] today —
 * `androidx.appfunctions` was not on the classpath during the SDK pass that
 * settled the Apple side, so no Android schema has been verified either way.
 * When that pass happens, entries here flip to [AndroidSchemaBinding.Predefined]
 * or [AndroidSchemaBinding.None] individually; this registry is the single
 * place that changes.
 */
object AndroidCanonBindingRegistry {

    private val bindings: Map<CanonType, AndroidCanonBinding> =
        CanonType.entries.associateWith { AndroidCanonBinding() }

    /** The Android binding for [type]. Defaults to [AndroidSchemaBinding.PendingSdkVerification]. */
    fun bindingFor(type: CanonType): AndroidCanonBinding = bindings[type] ?: AndroidCanonBinding()
}
