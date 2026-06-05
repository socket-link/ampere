package link.socket.ampere.eval.meter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.eval.trace.Trace

/** AMPR-185 task 3.3 validation. */
class CompositeMeterTest {

    private val alwaysPass = Tolerance(minScore = 0.0)

    @Test
    fun `aggregate score equals the weighted mean of children`() = runTest {
        val composite = CompositeMeter(
            meterId = "composite",
            tolerance = alwaysPass,
            children = listOf(
                WeightedMeter(meter = fixedMeter("a", 0.8), weight = 1.0),
                WeightedMeter(meter = fixedMeter("b", 0.4), weight = 3.0),
            ),
        )
        val reading = composite.measure(anyTrace()).getOrThrow()

        // (1.0 * 0.8 + 3.0 * 0.4) / 4.0 = (0.8 + 1.2) / 4.0 = 0.5
        assertEquals(0.5, reading.score)
    }

    @Test
    fun `passes when all required children pass`() = runTest {
        val composite = CompositeMeter(
            meterId = "composite",
            tolerance = Tolerance(0.5),
            children = listOf(
                WeightedMeter(meter = fixedMeter("a", 1.0), weight = 1.0, required = true),
                WeightedMeter(meter = fixedMeter("b", 1.0), weight = 1.0, required = true),
            ),
        )
        val reading = composite.measure(anyTrace()).getOrThrow()

        assertTrue(reading.passed)
    }

    @Test
    fun `one failing required child fails the composite`() = runTest {
        val composite = CompositeMeter(
            meterId = "composite",
            tolerance = Tolerance(0.0),
            children = listOf(
                WeightedMeter(meter = fixedMeter("a", 1.0), weight = 1.0, required = true),
                WeightedMeter(meter = fixedMeter("b", 0.0), weight = 1.0, required = true),
            ),
        )
        val reading = composite.measure(anyTrace()).getOrThrow()

        assertFalse(reading.passed)
    }

    @Test
    fun `required child failure propagates immediately as Result failure`() = runTest {
        val boom = MeterError.EmptyTrace("boom")
        val failingMeter = Meter { _ -> Result.failure(boom) }
        val composite = CompositeMeter(
            meterId = "composite",
            tolerance = alwaysPass,
            children = listOf(
                WeightedMeter(meter = failingMeter, weight = 1.0, required = true),
            ),
        )
        val result = composite.measure(anyTrace())

        assertTrue(result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun `non-required child failure is skipped`() = runTest {
        val failingMeter = Meter { _ -> Result.failure(MeterError.EmptyTrace("optional")) }
        val composite = CompositeMeter(
            meterId = "composite",
            tolerance = alwaysPass,
            children = listOf(
                WeightedMeter(meter = failingMeter, weight = 1.0, required = false),
                WeightedMeter(meter = fixedMeter("b", 0.6), weight = 1.0),
            ),
        )
        val reading = composite.measure(anyTrace()).getOrThrow()

        assertEquals(0.6, reading.score)
    }

    @Test
    fun `all children failing returns NoReadings`() = runTest {
        val failingMeter = Meter { _ -> Result.failure(MeterError.EmptyTrace("x")) }
        val composite = CompositeMeter(
            meterId = "composite",
            tolerance = alwaysPass,
            children = listOf(WeightedMeter(meter = failingMeter, weight = 1.0, required = false)),
        )
        val result = composite.measure(anyTrace())

        assertTrue(result.isFailure)
        assertIs<MeterError.NoReadings>(result.exceptionOrNull())
    }

    // region — fixtures

    private fun anyTrace() = Trace(
        id = "t",
        runId = "r",
        arcId = "a",
        createdAt = 0L,
        events = listOf(
            link.socket.ampere.eval.trace.TraceEvent(
                index = 0,
                timestamp = 1L,
                type = "Anything",
                payload = kotlinx.serialization.json.buildJsonObject {},
            ),
        ),
    )

    private fun fixedMeter(id: String, score: Double): Meter {
        val reading = Reading(score = score, passed = score >= 0.5, meterId = id)
        return Meter { _ -> Result.success(reading) }
    }

    // endregion
}
