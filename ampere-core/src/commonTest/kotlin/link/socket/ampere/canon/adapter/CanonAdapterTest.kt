package link.socket.ampere.canon.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.canon.CanonRing
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.DocumentKind
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

class CanonAdapterTest {

    private val observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private val handle = SourceHandle(
        linkId = LinkId("google-oauth-1"),
        sourceSystem = "apple.mail",
        nativeId = "msg-42",
    )

    /**
     * A realistic native object: three fields the canon projection covers and
     * four it does not. The four are the ones a naive write-back destroys.
     */
    private fun nativeMail() = NativePayload(
        schema = "MailMessageEntity",
        fields = JsonObject(
            mapOf(
                "subject" to JsonPrimitive("Quarterly review"),
                "bodyText" to JsonPrimitive("See attached."),
                "isRead" to JsonPrimitive(false),
                "mimeStructure" to JsonPrimitive("multipart/mixed"),
                "rawHeaders" to JsonPrimitive("Received: from mx.example"),
                "providerLabels" to JsonPrimitive("IMPORTANT,CATEGORY_UPDATES"),
                "threadId" to JsonPrimitive("thread-7"),
            ),
        ),
    )

    private fun failureOf(result: Result<*>): CanonConversionFailure {
        val error = result.exceptionOrNull()
        assertIs<CanonConversionException>(error)
        return error.failure
    }

    // -----------------------------------------------------------------
    // Projection
    // -----------------------------------------------------------------

    @Test
    fun `projection carries provenance the adapter cannot omit`() {
        val adapter = MailMessageAdapter(FakeNativeStore())
        val payload = nativeMail()

        val entity = adapter.project(payload, handle, observedAt).getOrThrow()

        assertEquals(handle, entity.provenance.sourceHandle)
        assertEquals(observedAt, entity.provenance.observedAt)
        assertEquals(payload, entity.provenance.nativePayload)
        assertEquals(CanonType.EMAIL_MESSAGE, entity.canonType)
        assertEquals(CanonRing.INTERCHANGE, entity.ring)
    }

    @Test
    fun `projection reads the fields the canon type owns`() {
        val adapter = MailMessageAdapter(FakeNativeStore())

        val entity = adapter.project(nativeMail(), handle, observedAt).getOrThrow()

        assertEquals("Quarterly review", entity.subject)
        assertEquals("See attached.", entity.bodyText)
        assertEquals(false, entity.isRead)
    }

    @Test
    fun `projection can drop the native payload when the caller asks`() {
        val adapter = MailMessageAdapter(FakeNativeStore())

        val entity = adapter
            .project(nativeMail(), handle, observedAt, carryNativePayload = false)
            .getOrThrow()

        assertNull(entity.provenance.nativePayload)
    }

    @Test
    fun `projecting the wrong native schema fails rather than guessing`() {
        val adapter = MailMessageAdapter(FakeNativeStore())
        val wrongShape = NativePayload("CalendarEventEntity", JsonObject(emptyMap()))

        val failure = failureOf(adapter.project(wrongShape, handle, observedAt))

        assertIs<CanonConversionFailure.SchemaMismatch>(failure)
        assertEquals("MailMessageEntity", failure.expectedSchema)
        assertEquals("CalendarEventEntity", failure.actualSchema)
    }

    @Test
    fun `a missing required field is a typed failure and never a throw`() {
        val adapter = MailMessageAdapter(FakeNativeStore())
        val empty = NativePayload("MailMessageEntity", JsonObject(emptyMap()))

        val failure = failureOf(adapter.project(empty, handle, observedAt))

        assertIs<CanonConversionFailure.MissingRequiredField>(failure)
        assertEquals("subject", failure.field)
    }

    // -----------------------------------------------------------------
    // Round-trip
    // -----------------------------------------------------------------

    @Test
    fun `canon to native round-trip is byte-identical when nothing changed`() = runTest {
        val adapter = MailMessageAdapter(FakeNativeStore())
        val original = nativeMail()

        val entity = adapter.project(original, handle, observedAt).getOrThrow()
        val merged = adapter.mergeForWriteBack(entity).getOrThrow()

        assertEquals(original, merged)
    }

    @Test
    fun `document round-trip preserves the fan-out discriminator`() = runTest {
        val adapter = FileDocumentAdapter(FakeNativeStore())
        val original = NativePayload(
            schema = "FileEntity",
            fields = JsonObject(
                mapOf(
                    "title" to JsonPrimitive("Roadmap"),
                    "documentKind" to JsonPrimitive("presentation"),
                    "mimeType" to JsonPrimitive("application/vnd.apple.keynote"),
                    "revisionHistory" to JsonPrimitive("r1,r2,r3"),
                ),
            ),
        )

        val entity = adapter.project(original, handle, observedAt).getOrThrow()
        assertEquals(DocumentKind.PRESENTATION, entity.kind)

        val merged = adapter.mergeForWriteBack(entity).getOrThrow()
        assertEquals(original, merged)
    }

    @Test
    fun `an unmappable document kind is a typed failure`() {
        val adapter = FileDocumentAdapter(FakeNativeStore())
        val payload = NativePayload(
            schema = "FileEntity",
            fields = JsonObject(
                mapOf(
                    "title" to JsonPrimitive("Mystery"),
                    "documentKind" to JsonPrimitive("hologram"),
                ),
            ),
        )

        val failure = failureOf(adapter.project(payload, handle, observedAt))

        assertIs<CanonConversionFailure.MalformedField>(failure)
        assertEquals("documentKind", failure.field)
    }

    // -----------------------------------------------------------------
    // Preserve-and-merge write-back — the non-negotiable
    // -----------------------------------------------------------------

    @Test
    fun `write-back preserves every native field the projection dropped`() = runTest {
        val store = FakeNativeStore(mapOf("msg-42" to nativeMail()))
        val adapter = MailMessageAdapter(store)

        val entity = adapter.project(nativeMail(), handle, observedAt).getOrThrow()
        val mutated = entity.copy(subject = "Quarterly review (revised)", isRead = true)

        adapter.writeBack(mutated).getOrThrow()

        val written = store.read("msg-42")!!

        // Canon-owned fields changed...
        assertEquals(JsonPrimitive("Quarterly review (revised)"), written.fields["subject"])
        assertEquals(JsonPrimitive(true), written.fields["isRead"])

        // ...and everything the projection never saw is byte-identical.
        assertEquals(JsonPrimitive("multipart/mixed"), written.fields["mimeStructure"])
        assertEquals(JsonPrimitive("Received: from mx.example"), written.fields["rawHeaders"])
        assertEquals(JsonPrimitive("IMPORTANT,CATEGORY_UPDATES"), written.fields["providerLabels"])
        assertEquals(JsonPrimitive("thread-7"), written.fields["threadId"])
    }

    @Test
    fun `write-back leaves an owned field alone when the canon value is absent`() = runTest {
        val store = FakeNativeStore(mapOf("msg-42" to nativeMail()))
        val adapter = MailMessageAdapter(store)

        val entity = adapter.project(nativeMail(), handle, observedAt).getOrThrow()

        // bodyText is owned but null on the canon entity — a delta, not a document.
        adapter.writeBack(entity.copy(bodyText = null)).getOrThrow()

        assertEquals(JsonPrimitive("See attached."), store.read("msg-42")!!.fields["bodyText"])
    }

    @Test
    fun `an adapter writing outside its owned fields is refused`() = runTest {
        val store = FakeNativeStore(mapOf("msg-42" to nativeMail()))
        val adapter = OverreachingMailAdapter(store)

        val entity = adapter.project(nativeMail(), handle, observedAt).getOrThrow()
        val failure = failureOf(adapter.writeBack(entity))

        assertIs<CanonConversionFailure.UnownedFieldWrite>(failure)
        assertEquals("providerLabels", failure.field)

        // And nothing was written.
        assertEquals(JsonPrimitive("IMPORTANT,CATEGORY_UPDATES"), store.read("msg-42")!!.fields["providerLabels"])
    }

    @Test
    fun `write-back re-fetches when the entity carries no native payload`() = runTest {
        val store = FakeNativeStore(mapOf("msg-42" to nativeMail()))
        val adapter = MailMessageAdapter(store)

        val entity = adapter
            .project(nativeMail(), handle, observedAt, carryNativePayload = false)
            .getOrThrow()
        assertNull(entity.provenance.nativePayload)

        adapter.writeBack(entity.copy(subject = "Re-fetched")).getOrThrow()

        val written = store.read("msg-42")!!
        assertEquals(JsonPrimitive("Re-fetched"), written.fields["subject"])
        assertEquals(JsonPrimitive("thread-7"), written.fields["threadId"])
    }

    @Test
    fun `a failed re-fetch is a typed failure and writes nothing`() = runTest {
        val store = FakeNativeStore(mapOf("msg-42" to nativeMail()))
        val adapter = MailMessageAdapter(store)

        val entity = adapter
            .project(nativeMail(), handle, observedAt, carryNativePayload = false)
            .getOrThrow()

        store.failNextFetch = "network down"
        val failure = failureOf(adapter.writeBack(entity.copy(subject = "Never written")))

        assertIs<CanonConversionFailure.SourceUnavailable>(failure)
        assertEquals("msg-42", failure.nativeId)
        assertTrue(failure.reason.contains("network down"))

        assertEquals(JsonPrimitive("Quarterly review"), store.read("msg-42")!!.fields["subject"])
    }

    @Test
    fun `merging against a mismatched native schema is refused`() = runTest {
        val adapter = MailMessageAdapter(FakeNativeStore())

        val entity = adapter.project(nativeMail(), handle, observedAt).getOrThrow()
        val corrupted = entity.copy(
            provenance = entity.provenance.copy(
                nativePayload = NativePayload("CalendarEventEntity", JsonObject(emptyMap())),
            ),
        )

        val failure = failureOf(adapter.mergeForWriteBack(corrupted))

        assertIs<CanonConversionFailure.SchemaMismatch>(failure)
    }

    // -----------------------------------------------------------------
    // Read-only adapters have no write surface
    // -----------------------------------------------------------------

    @Test
    fun `a read-only adapter projects without exposing any write surface`() {
        val adapter = ReadOnlyMailAdapter(FakeNativeStore())

        val entity = adapter.project(nativeMail(), handle, observedAt).getOrThrow()

        assertEquals("Quarterly review", entity.subject)
        // `adapter.writeBack(entity)` does not compile here — `ReadableCanonAdapter`
        // declares no such member, so there is nothing to call.
    }

    // -----------------------------------------------------------------
    // Creation
    // -----------------------------------------------------------------

    @Test
    fun `create with an unowned field fails UnownedFieldWrite`() = runTest {
        val adapter = OverreachingCreatingMailAdapter(FakeNativeStore())
        val draft = adapter.project(nativeMail(), handle, observedAt).getOrThrow()

        val failure = failureOf(adapter.create(draft))

        assertIs<CanonConversionFailure.UnownedFieldWrite>(failure)
        assertEquals("providerLabels", failure.field)
    }

    @Test
    fun `a created entity's returned handle re-projects to an equal entity`() = runTest {
        val store = FakeNativeStore()
        val adapter = CreatingMailAdapter(store)

        // The entity's own provenance is a placeholder — `create` never reads
        // it, since there is no existing native object to resolve a handle
        // against yet.
        val draft = adapter.project(nativeMail(), handle, observedAt).getOrThrow()
            .copy(subject = "New thread", bodyText = "Hello", isRead = false)

        val createdHandle = adapter.create(draft).getOrThrow()

        val createdPayload = store.read(createdHandle.nativeId)!!
        val reprojected = adapter.project(createdPayload, createdHandle, observedAt).getOrThrow()

        assertEquals(draft.subject, reprojected.subject)
        assertEquals(draft.bodyText, reprojected.bodyText)
        assertEquals(draft.isRead, reprojected.isRead)
    }

    // -----------------------------------------------------------------
    // Coverage bookkeeping
    // -----------------------------------------------------------------

    @Test
    fun `reference adapter coverage of Ring 1 is stated rather than assumed`() {
        // AMPR-222 ships the SPI; concrete adapters land per-Plug with each
        // transport. This test names which Ring 1 types have a reference
        // adapter today so the gap is visible instead of silently absent.
        val covered = setOf(CanonType.EMAIL_MESSAGE, CanonType.DOCUMENT)
        val ring1 = CanonType.inRing(CanonRing.INTERCHANGE)

        assertTrue(covered.all { it in ring1 })
        assertEquals(
            setOf(
                CanonType.PERSON,
                CanonType.EMAIL_DRAFT,
                CanonType.MAILBOX,
                CanonType.PHOTO,
                CanonType.PHOTO_ALBUM,
                CanonType.PLACE,
                CanonType.JOURNAL_ENTRY,
                CanonType.BOOK,
                CanonType.WEB_BOOKMARK,
                CanonType.BROWSER_TAB,
            ),
            ring1 - covered,
            "Ring 1 membership changed; update the reference-adapter coverage list",
        )
    }
}
