package link.socket.ampere.field.observe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.datetime.Instant
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonNote
import link.socket.ampere.canon.CanonPerson
import link.socket.ampere.canon.CanonProvenance
import link.socket.ampere.canon.SourceHandle
import link.socket.ampere.field.Decay
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Sensitivity
import link.socket.ampere.field.Subject
import link.socket.ampere.link.LinkId

/**
 * The canon → Field mapper (F7): a perceived entity becomes an Observation held by the perceiving
 * Subject, about the entity's Link-qualified ref, with the perceive event as its provenance.
 */
class CanonObservationTest {

    private val observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val recordedAt = Instant.fromEpochMilliseconds(1_700_000_000_250)
    private val holder: Ref<Subject> = Ref("scout")

    private fun provenance(linkId: String = "link-1", perceiveEventId: String? = "perceive-1") = CanonProvenance(
        sourceHandle = SourceHandle(LinkId(linkId), sourceSystem = "apple.notes", nativeId = "note-1"),
        observedAt = observedAt,
        perceiveEventId = perceiveEventId,
    )

    @Test
    fun `a perceived entity becomes a settled observation whose provenance is its perceive event`() {
        val note = CanonNote(CanonId("note-1"), provenance())

        val belief = note.toObservation(holder, recordedAt).getOrThrow()

        assertEquals(holder, belief.holder)
        assertEquals(note.environmentRef, belief.subject)
        assertEquals("perceive-1", belief.provenance.createdBy)
        assertEquals(recordedAt, belief.provenance.createdAt)
        assertEquals(OBSERVATION_CONFIDENCE, belief.confidence)
        assertEquals(Sensitivity.PRIVATE, belief.sensitivity)
        assertEquals(Decay.None, belief.decay)
    }

    @Test
    fun `an observation of a person is protected by default`() {
        val person = CanonPerson(CanonId("ada"), provenance(), displayName = "Ada")

        val belief = person.toObservation(holder, recordedAt).getOrThrow()

        assertEquals(Sensitivity.PROTECTED, belief.sensitivity)
    }

    @Test
    fun `the content states the observation and never copies the entity's fields`() {
        val person = CanonPerson(CanonId("ada"), provenance(), displayName = "Ada Lovelace")

        val content = person.toObservation(holder, recordedAt).getOrThrow().content

        assertFalse(content.contains("Lovelace"), "a perceived person's fields leaked into $content")
    }

    @Test
    fun `an entity with no perceive event cannot enter the Field`() {
        val note = CanonNote(CanonId("note-1"), provenance(perceiveEventId = null))

        val failure = note.toObservation(holder, recordedAt).exceptionOrNull()

        val unrecorded = assertIs<UnrecordedObservationException>(failure)
        assertEquals(CanonId("note-1"), unrecorded.canonId)
    }

    @Test
    fun `the same canon id over two links is two environment refs`() {
        // A CanonId is Link-scoped (the same-Link rule): a bare-id ref would merge two entities.
        val fromOne = CanonNote(CanonId("note-1"), provenance(linkId = "link-1"))
        val fromTwo = CanonNote(CanonId("note-1"), provenance(linkId = "link-2"))

        assertNotEquals(fromOne.environmentRef, fromTwo.environmentRef)
    }

    @Test
    fun `a slash in a link id cannot make two link and id pairs collide`() {
        val left = canonEnvironmentRef(LinkId("a/b"), CanonId("c"))
        val right = canonEnvironmentRef(LinkId("a"), CanonId("b/c"))

        assertNotEquals(left, right)
        assertEquals("canon/a%2Fb/c", left.value)
        assertEquals("canon/a/b/c", right.value)
    }
}
