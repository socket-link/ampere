package link.socket.ampere.canon.adapter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.canon.CanonDocument
import link.socket.ampere.canon.CanonEmailMessage
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.DocumentKind
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle

/**
 * Reference adapters used to exercise the [ReadableCanonAdapter] /
 * [WritableCanonAdapter] contracts.
 *
 * They live here — not in `ampere-core`'s own test source set — because
 * consumers outside `ampere-core` need them too: `ampere-bindings-apple`'s
 * tests cross-check `AppleCanonBinding.lossyFields` against these adapters'
 * `ownedFields` (AMPR-257). [FakeNativeStore] moved here first, for the same
 * cross-module-visibility reason (AMPR-250).
 *
 *  - [MailMessageAdapter] — an entity-schema binding with a write path.
 *  - [FileDocumentAdapter] — the `Document` fan-out, where the discriminator is
 *    itself the lossy axis.
 *
 * [mailFields] / [projectMailFields] are also used by the mail-shaped fixtures
 * that stay in `ampere-core`'s own test source set (`ReadOnlyMailAdapter`,
 * `CreatingMailAdapter`), so they are public rather than file-private.
 */

fun mailFields(entity: CanonEmailMessage): Map<String, JsonElement> =
    buildMap {
        put("subject", JsonPrimitive(entity.subject))
        entity.bodyText?.let { put("bodyText", JsonPrimitive(it)) }
        put("isRead", JsonPrimitive(entity.isRead))
    }

fun projectMailFields(
    canonType: CanonType,
    nativeSchema: NativeSchema,
    fields: JsonObject,
    provenance: CanonProvenance,
): Result<CanonEmailMessage> =
    NativeFields.project(fields, canonType, nativeSchema) { mail ->
        CanonEmailMessage(
            canonId = CanonId(provenance.sourceHandle.nativeId),
            provenance = provenance,
            subject = mail.requireString("subject"),
            from = null,
            bodyText = mail.optionalString("bodyText"),
            isRead = mail.optionalBoolean("isRead", default = false),
        )
    }

class MailMessageAdapter(
    private val store: FakeNativeStore,
) : WritableCanonAdapter<CanonEmailMessage>() {

    override val canonType: CanonType = CanonType.EMAIL_MESSAGE
    override val nativeSchema: NativeSchema = NativeSchema("MailMessageEntity")
    override val ownedFields: Set<String> = setOf("subject", "bodyText", "isRead")

    override fun projectFields(
        fields: JsonObject,
        provenance: CanonProvenance,
    ): Result<CanonEmailMessage> = projectMailFields(canonType, nativeSchema, fields, provenance)

    override fun canonFields(entity: CanonEmailMessage): Result<Map<String, JsonElement>> =
        Result.success(mailFields(entity))

    override suspend fun fetchNative(handle: SourceHandle): Result<NativePayload> =
        store.fetch(handle.nativeId)

    override suspend fun writeNative(
        handle: SourceHandle,
        merged: NativePayload,
    ): Result<Unit> = runCatching { store.write(handle.nativeId, merged) }
}

class FileDocumentAdapter(
    private val store: FakeNativeStore,
) : WritableCanonAdapter<CanonDocument>() {

    override val canonType: CanonType = CanonType.DOCUMENT
    override val nativeSchema: NativeSchema = NativeSchema("FileEntity")
    override val ownedFields: Set<String> = setOf("title", "documentKind", "mimeType")

    override fun projectFields(
        fields: JsonObject,
        provenance: CanonProvenance,
    ): Result<CanonDocument> =
        NativeFields.project(fields, canonType, nativeSchema) { document ->
            val rawKind = document.optionalString("documentKind") ?: "file"
            val kind = DocumentKind.entries.firstOrNull { it.appleDomain == rawKind }
                ?: document.malformed<DocumentKind>(
                    "documentKind",
                    "no DocumentKind maps to Apple domain '$rawKind'",
                )

            CanonDocument(
                canonId = CanonId(provenance.sourceHandle.nativeId),
                provenance = provenance,
                title = document.requireString("title"),
                kind = kind,
                mimeType = document.optionalString("mimeType"),
            )
        }

    override fun canonFields(entity: CanonDocument): Result<Map<String, JsonElement>> =
        Result.success(
            buildMap {
                put("title", JsonPrimitive(entity.title))
                put("documentKind", JsonPrimitive(entity.kind.appleDomain))
                entity.mimeType?.let { put("mimeType", JsonPrimitive(it)) }
            },
        )

    override suspend fun fetchNative(handle: SourceHandle): Result<NativePayload> =
        store.fetch(handle.nativeId)

    override suspend fun writeNative(
        handle: SourceHandle,
        merged: NativePayload,
    ): Result<Unit> = runCatching { store.write(handle.nativeId, merged) }
}
