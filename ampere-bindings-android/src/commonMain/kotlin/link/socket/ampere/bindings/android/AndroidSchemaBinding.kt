package link.socket.ampere.bindings.android

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a canon type reaches Android's AppFunctions vocabulary.
 *
 * [PendingSdkVerification] is the honest default and the current state of every
 * canon type. `androidx.appfunctions` is not on this repo's classpath, so
 * `AppFunctionSchemaDefinition` could not be enumerated during recon — see the
 * "Recon caveats" section of `.context/issue-586-domain-type-canon-v1.md`.
 * Modelling the gap as a variant rather than a `null` keeps "we have not looked"
 * distinguishable from "we looked and there is nothing".
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
