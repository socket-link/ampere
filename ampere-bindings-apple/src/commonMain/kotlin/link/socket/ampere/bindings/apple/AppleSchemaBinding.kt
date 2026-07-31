package link.socket.ampere.bindings.apple

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
     * `appleSchema = .mail.message` form.
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
