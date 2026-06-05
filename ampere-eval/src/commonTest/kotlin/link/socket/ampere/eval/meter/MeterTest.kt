package link.socket.ampere.eval.meter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.eval.trace.Trace

/** AMPR-185 tasks 3.1 and 3.5 validation. */
class MeterTest {

    // region — task 3.1: core types

    @Test
    fun `AlwaysPassMeter returns score 1_0 and passed true`() = runTest {
        val meter = Meter { _ -> Result.success(Reading(score = 1.0, passed = true, meterId = "always-pass")) }
        val reading = meter.measure(emptyTrace()).getOrThrow()

        assertEquals(1.0, reading.score)
        assertTrue(reading.passed)
    }

    @Test
    fun `Tolerance passes when score meets minScore`() {
        val t = Tolerance(minScore = 0.5)
        assertTrue(t.passes(0.5))
        assertTrue(t.passes(1.0))
    }

    @Test
    fun `Tolerance fails when score is below minScore`() {
        val t = Tolerance(minScore = 0.5)
        assertTrue(!t.passes(0.49))
        assertTrue(!t.passes(0.0))
    }

    @Test
    fun `Reading detail defaults to empty map`() {
        val r = Reading(score = 0.8, passed = true, meterId = "m")
        assertEquals(emptyMap(), r.detail)
    }

    // endregion

    // region — task 3.5: failure discipline

    @Test
    fun `meter handed a malformed (empty) trace returns typed failure`() = runTest {
        val meter = OutcomeMeter(
            meterId = "outcome",
            tolerance = Tolerance(1.0),
            predicate = { true },
        )
        val result = meter.measure(emptyTrace())

        assertTrue(result.isFailure)
        assertIs<MeterError.EmptyTrace>(result.exceptionOrNull())
    }

    // endregion

    // region — fixtures

    private fun emptyTrace() = Trace(id = "t", runId = "r", arcId = "a", createdAt = 0L, events = emptyList())

    // endregion
}
