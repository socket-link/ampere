package link.socket.ampere.canon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Declarative platform binding for a [CanonType].
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
data class CanonBinding(
    val apple: AppleSchemaBinding? = null,
    val android: AndroidSchemaBinding = AndroidSchemaBinding.PendingSdkVerification,
    val lossyFields: List<String> = emptyList(),
) {
    companion object {
        /** A canon type with no platform binding at all — Ring 3 service types. */
        val UNBOUND: CanonBinding = CanonBinding(
            apple = null,
            android = AndroidSchemaBinding.None,
        )
    }
}

/**
 * How a canon type reaches Apple's cross-app vocabulary.
 *
 * Two shapes exist because Apple ships two mechanisms: named entity schemas
 * grouped into domains (`mail.message`), and free-floating *system value types*
 * usable through `IntentValueRepresentation` / `Transferable` with no schema at
 * all (`IntentPerson`, `CLPlacemark`).
 */
@Serializable
sealed interface AppleSchemaBinding {

    /** The SDK identifier, e.g. `MailMessageEntity` or `IntentPerson`. */
    val identifier: String

    /**
     * A system-defined entity schema, addressed as `domain.accessor` — the
     * `appleSchema = .mail.message` form from the ticket.
     */
    @Serializable
    @SerialName("apple_schema.entity")
    data class EntitySchema(
        val domain: String,
        val accessor: String,
        override val identifier: String,
    ) : AppleSchemaBinding {
        /** The `.mail.message` address, rendered. */
        val qualifiedName: String get() = "$domain.$accessor"
    }

    /**
     * An Apple system value type. These carry no domain because they are not
     * scoped to one — any intent parameter can be one.
     */
    @Serializable
    @SerialName("apple_schema.system_value")
    data class SystemValueType(
        override val identifier: String,
    ) : AppleSchemaBinding
}

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
