package link.socket.ampere.canon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.link.LinkId

/**
 * [CanonProse] (AMPR-268): the bounded-prose counterpart to [CanonTablePreview]
 * for canon types whose free-form text does not decompose into rows and
 * columns.
 */
class CanonProseTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val provenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("link-1"),
            sourceSystem = "apple.files",
            nativeId = "doc-1",
        ),
        observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    private fun documentCarrying(plainText: CanonProse) = CanonDocument(
        canonId = CanonId("doc-1"),
        provenance = provenance,
        title = "Spec",
        kind = DocumentKind.WORD_PROCESSOR,
        plainText = plainText,
    )

    private fun roundTrip(entity: CanonEntity): CanonEntity =
        json.decodeFromString(
            CanonEntity.serializer(),
            json.encodeToString(CanonEntity.serializer(), entity),
        )

    @Test
    fun `short prose survives bounding untouched`() {
        val text = "A few sentences of plain text."

        val prose = CanonProse.bounded(text)

        assertEquals(text, prose.text)
        assertFalse(prose.truncated, "nothing was cut; truncated must not claim otherwise")
        assertTrue(prose.isWithinBounds)
    }

    @Test
    fun `bounding truncates text past the char limit`() {
        val long = "x".repeat(CanonProse.MAX_CHARS * 10)

        val prose = CanonProse.bounded(long)

        assertEquals(CanonProse.MAX_CHARS, prose.text.length)
        assertTrue(prose.truncated, "text over the limit was cut")
        assertTrue(prose.isWithinBounds)
    }

    @Test
    fun `a hand-built oversized value reports itself out of bounds`() {
        // The bound is a write-side factory rule, not a decode-time reject: an
        // oversized value must still decode, or a recorded trace carrying one
        // becomes permanently unreplayable. isWithinBounds is how it stays visible.
        val oversized = CanonProse(text = "x".repeat(CanonProse.MAX_CHARS + 1))

        assertFalse(oversized.isWithinBounds)
        assertEquals(oversized, roundTrip(documentCarrying(oversized)).let { (it as CanonDocument).plainText })
    }

    @Test
    fun `bounding never splits a surrogate pair`() {
        // A lone high surrogate is not valid UTF-8; it would encode as a
        // replacement character and break the round trip these bounds protect.
        // The leading "a" shifts the pairs so the cut index lands on a high
        // surrogate; without the offset the boundary falls harmlessly between
        // whole pairs and the hazard never fires.
        val text = "a" + "𝄞".repeat(CanonProse.MAX_CHARS)
        assertTrue(text[CanonProse.MAX_CHARS - 1].isHighSurrogate(), "fixture must straddle the bound")

        val prose = CanonProse.bounded(text)

        assertFalse(prose.text.last().isHighSurrogate(), "bounding left a lone high surrogate")
        assertEquals(prose, roundTrip(documentCarrying(prose)).let { (it as CanonDocument).plainText })
    }
}
