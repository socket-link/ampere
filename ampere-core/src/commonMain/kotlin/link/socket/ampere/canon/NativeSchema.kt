package link.socket.ampere.canon

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * The identifier of a native shape an adapter reads and writes — `EKEvent`, `CNContact`,
 * `MailMessageEntity`.
 *
 * [link.socket.ampere.canon.adapter.ReadableCanonAdapter.nativeSchema] and [NativePayload.schema]
 * are compared on every projection and every merge. When the two halves of a projection are
 * written in different source sets — the field-reading half in commonMain, the framework glue
 * that produces the native payload in a platform set — a bare string literal is duplicated
 * across files with nothing tying them together, and a typo surfaces as a
 * [link.socket.ampere.canon.adapter.CanonConversionFailure.SchemaMismatch] at runtime rather
 * than a compile error.
 *
 * Declaring the schema once as a named constant of this type and referencing it from both
 * halves closes that seam. Wrapping it in a value class costs nothing at runtime and makes
 * "which string is this?" answerable from the type.
 */
@JvmInline
@Serializable
value class NativeSchema(val value: String) {
    init {
        require(value.isNotBlank()) { "NativeSchema must not be blank" }
    }

    override fun toString(): String = value
}
