package link.socket.ampere.agents.domain.emission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive

class EmissionDigestTest {

    private val provenance = EmissionProvenance(inputDigest = "irrelevant")
    private val producedAt = Instant.fromEpochMilliseconds(0)

    @Test
    fun `digest is deterministic across calls`() {
        val payload = EmissionPayload.Confirmation(
            action = "rm -rf node_modules",
            preview = null,
            dangerLevel = DangerLevel.MEDIUM,
        )
        val a = inputDigest(payload)
        val b = inputDigest(payload)
        assertEquals(a, b)
    }

    @Test
    fun `digest is 16 hex chars`() {
        val digest = inputDigest(EmissionPayload.Prose(text = "anything", format = ProseFormat.PLAIN))
        assertEquals(16, digest.length)
        assertTrue(digest.all { it in "0123456789abcdef" }, "Expected lowercase hex, got: $digest")
    }

    @Test
    fun `equal Confirmations produce equal digests`() {
        val a = EmissionPayload.Confirmation("act", "preview", DangerLevel.LOW)
        val b = EmissionPayload.Confirmation("act", "preview", DangerLevel.LOW)
        assertEquals(inputDigest(a), inputDigest(b))
    }

    @Test
    fun `Confirmations differing in action produce different digests`() {
        val a = EmissionPayload.Confirmation("delete x", null, DangerLevel.HIGH)
        val b = EmissionPayload.Confirmation("delete y", null, DangerLevel.HIGH)
        assertNotEquals(inputDigest(a), inputDigest(b))
    }

    @Test
    fun `Confirmations differing in danger level produce different digests`() {
        val a = EmissionPayload.Confirmation("act", null, DangerLevel.LOW)
        val b = EmissionPayload.Confirmation("act", null, DangerLevel.HIGH)
        assertNotEquals(inputDigest(a), inputDigest(b))
    }

    @Test
    fun `Prose differing in format produces different digests`() {
        val a = EmissionPayload.Prose("hello", ProseFormat.PLAIN)
        val b = EmissionPayload.Prose("hello", ProseFormat.MARKDOWN)
        assertNotEquals(inputDigest(a), inputDigest(b))
    }

    @Test
    fun `digest differs between payload kinds for identical content`() {
        val sensor = EmissionPayload.Sensor(label = "x", value = "y", unit = null, refreshUri = null)
        val prose = EmissionPayload.Prose(text = "x", format = ProseFormat.PLAIN)
        assertNotEquals(inputDigest(sensor), inputDigest(prose))
    }

    @Test
    fun `computeDedupKey returns digest for Confirmation`() {
        val payload = EmissionPayload.Confirmation("act", null, DangerLevel.LOW)
        val emission = Emission(
            id = "id-1",
            kind = EmissionKind.Confirmation,
            payload = payload,
            provenance = provenance,
            producedAt = producedAt,
        )
        assertEquals(inputDigest(payload), emission.computeDedupKey())
    }

    @Test
    fun `computeDedupKey returns null for Prose`() {
        val payload = EmissionPayload.Prose("hi", ProseFormat.PLAIN)
        val emission = Emission(
            id = "id-2",
            kind = EmissionKind.Prose,
            payload = payload,
            provenance = provenance,
            producedAt = producedAt,
        )
        assertNull(emission.computeDedupKey())
    }

    @Test
    fun `computeDedupKey returns null for Decision`() {
        val payload = EmissionPayload.Decision("Which?", null)
        val emission = Emission(
            id = "id-3",
            kind = EmissionKind.Decision,
            payload = payload,
            affordances = listOf(Affordance("aff-1", "Yes", JsonPrimitive(true))),
            provenance = provenance,
            producedAt = producedAt,
        )
        assertNull(emission.computeDedupKey())
    }

    @Test
    fun `computeDedupKey returns null for Sensor`() {
        val payload = EmissionPayload.Sensor("queue", "42", "items", null)
        val emission = Emission(
            id = "id-4",
            kind = EmissionKind.Sensor,
            payload = payload,
            provenance = provenance,
            producedAt = producedAt,
        )
        assertNull(emission.computeDedupKey())
    }
}
