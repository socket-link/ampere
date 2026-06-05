package link.socket.ampere.eval.meter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceEvent

/** AMPR-185 task 3.2 validation. */
class OutcomeMeterTest {

    private val passTolerance = Tolerance(minScore = 1.0)
    private val failTolerance = Tolerance(minScore = 0.0)

    @Test
    fun `passes on a matching terminal event with score 1_0`() = runTest {
        val meter = OutcomeMeter(
            meterId = "outcome",
            tolerance = passTolerance,
            predicate = { event -> event.type == "TaskCompleted" },
        )
        val reading = meter.measure(traceEndingWith("TaskCompleted")).getOrThrow()

        assertEquals(1.0, reading.score)
        assertTrue(reading.passed)
        assertEquals("outcome", reading.meterId)
        assertEquals(emptyMap(), reading.detail)
    }

    @Test
    fun `fails on a mismatched terminal event with score 0_0`() = runTest {
        val meter = OutcomeMeter(
            meterId = "outcome",
            tolerance = passTolerance,
            predicate = { event -> event.type == "TaskCompleted" },
        )
        val reading = meter.measure(traceEndingWith("TaskFailed")).getOrThrow()

        assertEquals(0.0, reading.score)
        assertFalse(reading.passed)
    }

    @Test
    fun `mismatch surfaces the terminal event type in detail`() = runTest {
        val meter = OutcomeMeter(
            meterId = "outcome",
            tolerance = passTolerance,
            predicate = { event -> event.type == "TaskCompleted" },
        )
        val reading = meter.measure(traceEndingWith("TaskFailed")).getOrThrow()

        assertEquals("TaskFailed", reading.detail["terminal_type"])
        assertEquals("false", reading.detail["match"])
    }

    @Test
    fun `empty trace returns typed failure`() = runTest {
        val meter = OutcomeMeter(
            meterId = "outcome",
            tolerance = passTolerance,
            predicate = { true },
        )
        val result = meter.measure(emptyTrace())

        assertTrue(result.isFailure)
        assertIs<MeterError.EmptyTrace>(result.exceptionOrNull())
    }

    @Test
    fun `tolerance determines pass flag independently of match`() = runTest {
        val partial = Tolerance(minScore = 0.0)
        val meter = OutcomeMeter(
            meterId = "outcome",
            tolerance = partial,
            predicate = { false },
        )
        val reading = meter.measure(traceEndingWith("Anything")).getOrThrow()

        assertEquals(0.0, reading.score)
        assertTrue(reading.passed) // minScore=0.0 means 0.0 passes
    }

    // region — fixtures

    private fun emptyTrace() = Trace(id = "t", runId = "r", arcId = "a", createdAt = 0L, events = emptyList())

    private fun traceEndingWith(eventType: String): Trace {
        val events = listOf(
            TraceEvent(index = 0, timestamp = 1L, type = "Start", payload = buildJsonObject {}),
            TraceEvent(
                index = 1,
                timestamp = 2L,
                type = eventType,
                payload = buildJsonObject { put("type", eventType) },
            ),
        )
        return Trace(id = "t", runId = "r", arcId = "a", createdAt = 0L, events = events)
    }

    // endregion
}
