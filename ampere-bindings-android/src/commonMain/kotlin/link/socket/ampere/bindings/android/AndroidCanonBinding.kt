package link.socket.ampere.bindings.android

import kotlinx.serialization.Serializable

/**
 * A [link.socket.ampere.canon.CanonType]'s projection onto Android's AppFunctions
 * vocabulary.
 *
 * See [link.socket.ampere.bindings.apple.AppleCanonBinding] for why bindings are
 * modelled as data rather than annotations.
 */
@Serializable
data class AndroidCanonBinding(
    val schema: AndroidSchemaBinding = AndroidSchemaBinding.PendingSdkVerification,
    val lossyFields: List<String> = emptyList(),
)
