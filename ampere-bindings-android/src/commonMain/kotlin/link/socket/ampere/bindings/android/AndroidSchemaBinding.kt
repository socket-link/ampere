package link.socket.ampere.bindings.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a canon type reaches Android's AppFunctions vocabulary.
 *
 * [PendingSdkVerification] is the honest default for a binding nobody has
 * checked. [AndroidCanonBindingRegistry] no longer carries it: the AMPR-510
 * pass enumerated `androidx.appfunctions` 1.0.0-beta01, found no predefined
 * schema at all, and set every canon type to [None]. Modelling the gap as a
 * variant rather than a `null` keeps "we have not looked" distinguishable from
 * "we looked and there is nothing".
 */
@Serializable
sealed interface AndroidSchemaBinding {

    /** Verified: Android defines no predefined schema for this type. */
    @Serializable
    @SerialName("android_schema.none")
    data object None : AndroidSchemaBinding

    /** Not yet checked against the AppFunctions SDK. */
    @Serializable
    @SerialName("android_schema.unverified")
    data object PendingSdkVerification : AndroidSchemaBinding

    /** A predefined `AppFunctionSchemaDefinition` this canon type binds to. */
    @Serializable
    @SerialName("android_schema.predefined")
    data class Predefined(
        val category: String,
        val schemaName: String,
        val schemaVersion: Int,
    ) : AndroidSchemaBinding
}
