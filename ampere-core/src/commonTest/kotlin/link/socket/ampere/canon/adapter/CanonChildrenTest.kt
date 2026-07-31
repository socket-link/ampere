package link.socket.ampere.canon.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.NativePayload
import link.socket.ampere.canon.NativeSchema
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.link.LinkId

class CanonChildrenTest {
    private val observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private val parent =
        CanonProvenance(
            sourceHandle =
            SourceHandle(
                linkId = LinkId("link-1"),
                sourceSystem = "apple.calendar",
                nativeId = "event-42",
                etag = "v3",
            ),
            observedAt = observedAt,
            nativePayload =
            NativePayload(NativeSchema("EKEvent"), JsonObject(mapOf("title" to JsonPrimitive("Design review")))),
        )

    @Test
    fun `a child does not carry the parent's native payload`() {
        // The payload is what makes write-back a pure merge. A child is not independently writable
        // through its parent's adapter, so carrying it would advertise a path that cannot work.
        val child = parent.forChild("event-42:attendee:ada@example.com")

        assertNull(child.nativePayload)
    }

    @Test
    fun `a child does not carry the parent's etag`() {
        // An etag is an optimistic-concurrency token for the parent object. Applied to a child it
        // would let a staleness check pass against the wrong record.
        val child = parent.forChild("event-42:place:self")

        assertNull(child.sourceHandle.etag)
    }

    @Test
    fun `a child is retargeted at its own native id but keeps the Link and the observation time`() {
        val child = parent.forChild("event-42:attendee:ada@example.com")

        assertEquals("event-42:attendee:ada@example.com", child.sourceHandle.nativeId)
        assertNotEquals(parent.sourceHandle.nativeId, child.sourceHandle.nativeId)
        // Same Link, same moment — the child was observed as part of the parent.
        assertEquals(parent.sourceHandle.linkId, child.sourceHandle.linkId)
        assertEquals(parent.sourceHandle.sourceSystem, child.sourceHandle.sourceSystem)
        assertEquals(observedAt, child.observedAt)
    }

    @Test
    fun `a natural key produces the same id regardless of when it is computed`() {
        assertEquals(
            childNativeId("event-42", "attendee", "ada@example.com"),
            childNativeId("event-42", "attendee", "ada@example.com"),
        )
    }

    @Test
    fun `the same key under different parents yields different ids`() {
        // Scoping to the parent is what stops two events sharing an attendee from collapsing into
        // one canon identity — an assertion this adapter has no basis to make.
        assertNotEquals(
            childNativeId("event-42", "attendee", "ada@example.com"),
            childNativeId("event-99", "attendee", "ada@example.com"),
        )
    }

    @Test
    fun `the same key in different roles yields different ids`() {
        assertNotEquals(
            childNativeId("event-42", "attendee", "self"),
            childNativeId("event-42", "place", "self"),
        )
    }

    @Test
    fun `a positional id is visibly positional`() {
        // The point of the separate function is that a reader can see the instability at the call
        // site and in the resulting id, rather than it hiding inside a string template.
        val id = unstableChildNativeId("event-42", "attendee", index = 3)

        assertTrue(id.contains("#3"), id)
        assertNotEquals(childNativeId("event-42", "attendee", "3"), id)
    }
}
