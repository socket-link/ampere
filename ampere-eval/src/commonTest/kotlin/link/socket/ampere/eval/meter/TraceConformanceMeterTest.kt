package link.socket.ampere.eval.meter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent

/** AMPR-187 task 5.3: the meter the golden-trace regression gate is built on. */
class TraceConformanceMeterTest {

    private val strict = Tolerance(minScore = 1.0)

    @Test
    fun `a rerun that differs only in ids and timing conforms exactly`() = runTest {
        val reference = trace(event("ArcSettled", settled(eventId = "a", timestamp = 1L, finalTick = 1)))
        val rerun = trace(event("ArcSettled", settled(eventId = "z", timestamp = 999L, finalTick = 1)))

        val reading = meter(reference).measure(rerun).getOrThrow()

        assertEquals(1.0, reading.score)
        assertTrue(reading.passed)
        assertEquals(emptyMap(), reading.detail)
    }

    @Test
    fun `a changed behavioral field diverges`() = runTest {
        val reference = trace(event("ArcSettled", buildJsonObject { put("finalTick", 1) }))
        val rerun = trace(event("ArcSettled", buildJsonObject { put("finalTick", 2) }))

        val reading = meter(reference).measure(rerun).getOrThrow()

        assertEquals(0.0, reading.score)
        assertFalse(reading.passed)
        assertEquals("0", reading.detail["first_divergence_index"])
    }

    @Test
    fun `volatile fields are dropped at every depth`() = runTest {
        val reference = trace(
            event(
                "CompletionManifestRecorded",
                buildJsonObject {
                    putJsonObject("record") {
                        put("runId", "run-1")
                        put("endedBy", "ERROR")
                    }
                },
            ),
        )
        val rerun = trace(
            event(
                "CompletionManifestRecorded",
                buildJsonObject {
                    putJsonObject("record") {
                        put("runId", "run-2")
                        put("endedBy", "ERROR")
                    }
                },
            ),
        )

        assertEquals(1.0, meter(reference).measure(rerun).getOrThrow().score)
    }

    @Test
    fun `volatile fields nested in an array are dropped too`() = runTest {
        fun withTally(id: String) = event(
            "ArcSettled",
            buildJsonObject {
                put(
                    "tallies",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("runId", id)
                                put("total", 3)
                            },
                        )
                    },
                )
            },
        )

        assertEquals(1.0, meter(trace(withTally("a"))).measure(trace(withTally("b"))).getOrThrow().score)
    }

    @Test
    fun `an extra event costs exactly one position`() = runTest {
        val reference = trace(event("ArcSettled", buildJsonObject { put("finalTick", 1) }))
        val rerun = trace(
            event("ArcSettled", buildJsonObject { put("finalTick", 1) }),
            event("CompletionManifestRecorded", buildJsonObject {}),
        )

        val reading = meter(reference).measure(rerun).getOrThrow()

        assertEquals(0.5, reading.score)
        assertFalse(reading.passed)
        assertEquals("1", reading.detail["first_divergence_index"])
        assertEquals("1", reading.detail["expected_event_count"])
        assertEquals("2", reading.detail["actual_event_count"])
        assertEquals("(absent)", reading.detail["expected"])
    }

    @Test
    fun `a truncated payload does not conform to an untruncated one`() = runTest {
        val reference = trace(event("ArcSettled", buildJsonObject { put("failure", "boom") }))
        val rerun = trace(event("ArcSettled", buildJsonObject { put("failure", "boom") }, truncated = true))

        assertEquals(0.0, meter(reference).measure(rerun).getOrThrow().score)
    }

    @Test
    fun `a narrower volatile set pins a field the default drops`() = runTest {
        val reference = trace(event("ArcSettled", buildJsonObject { put("runId", "run-1") }))
        val rerun = trace(event("ArcSettled", buildJsonObject { put("runId", "run-2") }))

        val pinned = TraceConformanceMeter(
            meterId = "conformance",
            reference = reference,
            tolerance = strict,
            volatileFields = setOf("eventId"),
        )

        assertEquals(0.0, pinned.measure(rerun).getOrThrow().score)
    }

    @Test
    fun `an empty reference is a typed configuration failure`() = runTest {
        val result = meter(trace()).measure(trace(event("ArcSettled", buildJsonObject {})))

        assertTrue(result.isFailure)
        assertIs<MeterError.EmptyReferenceTrace>(result.exceptionOrNull())
    }

    @Test
    fun `an empty graded trace is a typed failure`() = runTest {
        val reference = trace(event("ArcSettled", buildJsonObject {}))
        val result = meter(reference).measure(trace())

        assertTrue(result.isFailure)
        assertIs<MeterError.EmptyTrace>(result.exceptionOrNull())
    }

    // region — fixtures

    private fun settled(eventId: String, timestamp: Long, finalTick: Int) = buildJsonObject {
        put("eventId", eventId)
        put("timestamp", timestamp)
        put("finalTick", finalTick)
    }

    private fun meter(reference: Trace) = TraceConformanceMeter(
        meterId = "conformance",
        reference = reference,
        tolerance = strict,
    )

    private fun event(type: String, payload: JsonElement, truncated: Boolean = false) =
        TraceEvent(index = 0, timestamp = 0L, type = type, payload = payload, truncated = truncated)

    private fun trace(vararg events: TraceEvent) = Trace(
        id = "t",
        runId = "r",
        arcId = "startup-saas",
        createdAt = 0L,
        events = events.mapIndexed { index, event -> event.copy(index = index) },
    )

    // endregion
}
