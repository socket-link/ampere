package link.socket.ampere.canon.adapter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import link.socket.ampere.canon.CanonDocument
import link.socket.ampere.canon.CanonEmailMessage
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.DocumentKind
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

/**
 * Reference adapters used to exercise the [ReadableCanonAdapter] /
 * [WritableCanonAdapter] / [CreatingCanonAdapter] contracts.
 *
 * They live in the test source set on purpose. AMPR-222 ships the *SPI*; real
 * adapters are per-Plug work that lands with each transport. These fixtures
 * cover the binding shapes a real adapter has to handle:
 *
 *  - [MailMessageAdapter] — an entity-schema binding with a write path.
 *  - [FileDocumentAdapter] — the `Document` fan-out, where the discriminator is
 *    itself the lossy axis.
 *  - [OverreachingMailAdapter] — a deliberately broken adapter that writes
 *    outside its declared `ownedFields`, to prove the guard fires.
 *  - [ReadOnlyMailAdapter] — a read-only adapter with no write surface at all.
 *  - [CreatingMailAdapter] — a creating adapter, to exercise [CreatingCanonAdapter.create].
 *  - [OverreachingCreatingMailAdapter] — a creating adapter that overreaches
 *    its `ownedFields`, to prove `create` routes through the same guard as
 *    `writeBack`.
 */

/** A tiny in-memory stand-in for a native mail store. */
class FakeNativeStore(
    initial: Map<String, NativePayload> = emptyMap(),
) {
    private val rows = initial.toMutableMap()
    var failNextFetch: String? = null

    fun read(nativeId: String): NativePayload? = rows[nativeId]

    fun write(nativeId: String, payload: NativePayload) {
        rows[nativeId] = payload
    }

    fun fetch(nativeId: String): Result<NativePayload> {
        failNextFetch?.let { reason ->
            failNextFetch = null
            return Result.failure(IllegalStateException(reason))
        }
        return rows[nativeId]
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("no such native object: $nativeId"))
    }
}

private fun mailFields(entity: CanonEmailMessage): Map<String, JsonElement> =
    buildMap {
        put("subject", JsonPrimitive(entity.subject))
        entity.bodyText?.let { put("bodyText", JsonPrimitive(it)) }
        put("isRead", JsonPrimitive(entity.isRead))
    }

private fun projectMailFields(
    canonType: CanonType,
    nativeSchema: String,
    fields: JsonObject,
    provenance: CanonProvenance,
): Result<CanonEmailMessage> {
    val subject = fields["subject"]?.jsonPrimitive?.contentOrNull
        ?: return canonFailure(
            CanonConversionFailure.MissingRequiredField(canonType, "subject", nativeSchema),
        )

    return Result.success(
        CanonEmailMessage(
            canonId = CanonId(provenance.sourceHandle.nativeId),
            provenance = provenance,
            subject = subject,
            from = null,
            bodyText = fields["bodyText"]?.jsonPrimitive?.contentOrNull,
            isRead = fields["isRead"]?.jsonPrimitive?.booleanOrNull ?: false,
        ),
    )
}

class MailMessageAdapter(
    private val store: FakeNativeStore,
) : WritableCanonAdapter<CanonEmailMessage>() {

    override val canonType: CanonType = CanonType.EMAIL_MESSAGE
    override val nativeSchema: String = "MailMessageEntity"
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
    override val nativeSchema: String = "FileEntity"
    override val ownedFields: Set<String> = setOf("title", "documentKind", "mimeType")

    override fun projectFields(
        fields: JsonObject,
        provenance: CanonProvenance,
    ): Result<CanonDocument> {
        val title = fields["title"]?.jsonPrimitive?.contentOrNull
            ?: return canonFailure(
                CanonConversionFailure.MissingRequiredField(canonType, "title", nativeSchema),
            )

        val rawKind = fields["documentKind"]?.jsonPrimitive?.contentOrNull ?: "file"
        val kind = DocumentKind.entries.firstOrNull { it.appleDomain == rawKind }
            ?: return canonFailure(
                CanonConversionFailure.MalformedField(
                    canonType,
                    "documentKind",
                    "no DocumentKind maps to Apple domain '$rawKind'",
                ),
            )

        return Result.success(
            CanonDocument(
                canonId = CanonId(provenance.sourceHandle.nativeId),
                provenance = provenance,
                title = title,
                kind = kind,
                mimeType = fields["mimeType"]?.jsonPrimitive?.contentOrNull,
            ),
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

/**
 * Writes `providerLabels` — a field its canon projection never read and which
 * is not in [ownedFields]. Exists to prove the merge refuses it.
 */
class OverreachingMailAdapter(
    private val store: FakeNativeStore,
) : WritableCanonAdapter<CanonEmailMessage>() {

    override val canonType: CanonType = CanonType.EMAIL_MESSAGE
    override val nativeSchema: String = "MailMessageEntity"
    override val ownedFields: Set<String> = setOf("subject")

    override fun projectFields(
        fields: JsonObject,
        provenance: CanonProvenance,
    ): Result<CanonEmailMessage> = Result.success(
        CanonEmailMessage(
            canonId = CanonId(provenance.sourceHandle.nativeId),
            provenance = provenance,
            subject = fields["subject"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            from = null,
        ),
    )

    override fun canonFields(entity: CanonEmailMessage): Result<Map<String, JsonElement>> =
        Result.success(
            mapOf(
                "subject" to JsonPrimitive(entity.subject),
                "providerLabels" to JsonPrimitive("clobbered"),
            ),
        )

    override suspend fun fetchNative(handle: SourceHandle): Result<NativePayload> =
        store.fetch(handle.nativeId)

    override suspend fun writeNative(
        handle: SourceHandle,
        merged: NativePayload,
    ): Result<Unit> = runCatching { store.write(handle.nativeId, merged) }
}

/**
 * A read-only adapter. `ReadableCanonAdapter` declares no write member at all,
 * so there is no `ownedFields` to declare and no `WriteRejected` stub to
 * author — `readOnlyAdapter.writeBack(...)` does not compile, because
 * `writeBack` is not a member of this class.
 */
class ReadOnlyMailAdapter(
    private val store: FakeNativeStore,
) : ReadableCanonAdapter<CanonEmailMessage>() {

    override val canonType: CanonType = CanonType.EMAIL_MESSAGE
    override val nativeSchema: String = "MailMessageEntity"

    override fun projectFields(
        fields: JsonObject,
        provenance: CanonProvenance,
    ): Result<CanonEmailMessage> = projectMailFields(canonType, nativeSchema, fields, provenance)

    override suspend fun fetchNative(handle: SourceHandle): Result<NativePayload> =
        store.fetch(handle.nativeId)
}

/**
 * Exercises [CreatingCanonAdapter.create]: creates a new native mail object
 * rather than merging onto an existing one.
 */
class CreatingMailAdapter(
    private val store: FakeNativeStore,
) : CreatingCanonAdapter<CanonEmailMessage>() {

    override val canonType: CanonType = CanonType.EMAIL_MESSAGE
    override val nativeSchema: String = "MailMessageEntity"
    override val ownedFields: Set<String> = setOf("subject", "bodyText", "isRead")

    private var nextId = 0

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

    override suspend fun createNative(fields: JsonObject): Result<SourceHandle> {
        val nativeId = "created-${nextId++}"
        store.write(nativeId, NativePayload(schema = nativeSchema, fields = fields))
        return Result.success(
            SourceHandle(
                linkId = LinkId("google-oauth-1"),
                sourceSystem = "apple.mail",
                nativeId = nativeId,
            ),
        )
    }
}

/**
 * Creates with `providerLabels` — a field outside [ownedFields]. Exists to
 * prove [CreatingCanonAdapter.create] routes through the same guard
 * [WritableCanonAdapter.writeBack] does.
 */
class OverreachingCreatingMailAdapter(
    private val store: FakeNativeStore,
) : CreatingCanonAdapter<CanonEmailMessage>() {

    override val canonType: CanonType = CanonType.EMAIL_MESSAGE
    override val nativeSchema: String = "MailMessageEntity"
    override val ownedFields: Set<String> = setOf("subject")

    override fun projectFields(
        fields: JsonObject,
        provenance: CanonProvenance,
    ): Result<CanonEmailMessage> = projectMailFields(canonType, nativeSchema, fields, provenance)

    override fun canonFields(entity: CanonEmailMessage): Result<Map<String, JsonElement>> =
        Result.success(
            mapOf(
                "subject" to JsonPrimitive(entity.subject),
                "providerLabels" to JsonPrimitive("clobbered"),
            ),
        )

    override suspend fun fetchNative(handle: SourceHandle): Result<NativePayload> =
        store.fetch(handle.nativeId)

    override suspend fun writeNative(
        handle: SourceHandle,
        merged: NativePayload,
    ): Result<Unit> = runCatching { store.write(handle.nativeId, merged) }

    override suspend fun createNative(fields: JsonObject): Result<SourceHandle> {
        store.write("should-not-be-created", NativePayload(schema = nativeSchema, fields = fields))
        return Result.success(
            SourceHandle(
                linkId = LinkId("google-oauth-1"),
                sourceSystem = "apple.mail",
                nativeId = "should-not-be-created",
            ),
        )
    }
}
