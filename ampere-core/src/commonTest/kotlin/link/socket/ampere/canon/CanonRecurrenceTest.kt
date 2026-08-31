package link.socket.ampere.canon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

class CanonRecurrenceTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `a count-bounded recurrence is constructible`() {
        val recurrence = CanonRecurrence.of(every = 3.days, count = 8).getOrThrow()

        assertEquals(3.days, recurrence.every)
        assertEquals(8, recurrence.count)
        assertNull(recurrence.until)
        assertTrue(recurrence.isWithinBounds)
    }

    @Test
    fun `an until-bounded recurrence is constructible`() {
        val end = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val recurrence = CanonRecurrence.of(every = 12.hours, until = end).getOrThrow()

        assertNull(recurrence.count)
        assertEquals(end, recurrence.until)
        assertTrue(recurrence.isWithinBounds)
    }

    @Test
    fun `sub-daily intervals are legal because canon records intent`() {
        // EventKit's frequency enum bottoms out at daily; the gap is a binding
        // note, not a construction failure.
        val recurrence = CanonRecurrence.of(every = 30.minutes, count = 4).getOrThrow()

        assertEquals(30.minutes, recurrence.every)
    }

    @Test
    fun `a non-positive interval is rejected`() {
        assertTrue(CanonRecurrence.of(every = Duration.ZERO, count = 4).isFailure)
        assertTrue(CanonRecurrence.of(every = -(1.days), count = 4).isFailure)
    }

    @Test
    fun `a non-positive count is rejected`() {
        assertTrue(CanonRecurrence.of(every = 1.days, count = 0).isFailure)
        assertTrue(CanonRecurrence.of(every = 1.days, count = -3).isFailure)
    }

    @Test
    fun `an unbounded recurrence is rejected`() {
        assertTrue(CanonRecurrence.of(every = 1.days).isFailure)
    }

    @Test
    fun `an out-of-range recorded value still decodes`() {
        // The bound is a write-side factory precisely so a trace recorded by an
        // older or laxer writer stays replayable. Decode must never reject.
        val recorded = """{"every":"PT0S","count":-1,"until":null}"""

        val decoded = json.decodeFromString(CanonRecurrence.serializer(), recorded)

        assertEquals(Duration.ZERO, decoded.every)
        assertEquals(-1, decoded.count)
        assertFalse(decoded.isWithinBounds)
    }

    @Test
    fun `a recurrence round-trips`() {
        val recurrence = CanonRecurrence.of(
            every = 12.hours,
            until = Instant.fromEpochMilliseconds(1_700_000_000_000),
        ).getOrThrow()

        val encoded = json.encodeToString(CanonRecurrence.serializer(), recurrence)

        assertEquals(recurrence, json.decodeFromString(CanonRecurrence.serializer(), encoded))
    }
}
