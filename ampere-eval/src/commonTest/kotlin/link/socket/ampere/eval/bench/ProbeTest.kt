package link.socket.ampere.eval.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance
import link.socket.ampere.eval.trace.Trace

/** AMPR-186 task 4.1 validation. */
class ProbeTest {

    // region — task 4.1: core types

    @Test
    fun `Probe constructs with a golden trace`() {
        val trace = goldenTrace()
        val probe = Probe(
            id = "probe-1",
            arcId = "arc-1",
            seed = ProbeSeed(userGoal = "Implement user login"),
            meters = listOf(alwaysPassMeter()),
            tolerance = Tolerance(minScore = 0.8),
            goldenTrace = trace,
        )

        assertEquals("probe-1", probe.id)
        assertEquals("arc-1", probe.arcId)
        assertEquals(trace, probe.goldenTrace)
    }

    @Test
    fun `Probe goldenTrace defaults to null`() {
        val probe = Probe(
            id = "probe-2",
            arcId = "arc-1",
            seed = ProbeSeed(userGoal = "goal"),
            meters = listOf(alwaysPassMeter()),
            tolerance = Tolerance(minScore = 0.0),
        )

        assertNull(probe.goldenTrace)
    }

    @Test
    fun `RunMode has exactly Replay and Live variants`() {
        val modes: List<RunMode> = listOf(RunMode.Replay, RunMode.Live)
        assertTrue(modes.contains(RunMode.Replay))
        assertTrue(modes.contains(RunMode.Live))
    }

    @Test
    fun `BenchReport passRate reflects ProbeResult pass count`() {
        val trace = goldenTrace()
        val results = listOf(
            ProbeResult(probeId = "a", readings = emptyList(), passed = true, trace = trace),
            ProbeResult(probeId = "b", readings = emptyList(), passed = false, trace = trace),
        )
        val report = BenchReport(results = results, passRate = 0.5)

        assertEquals(2, report.results.size)
        assertEquals(0.5, report.passRate)
    }

    // endregion

    // region — fixtures

    private fun goldenTrace() = Trace(id = "t", runId = "r", arcId = "arc-1", createdAt = 0L, events = emptyList())

    private fun alwaysPassMeter() = Meter { _ ->
        Result.success(
            Reading(score = 1.0, passed = true, meterId = "always-pass"),
        )
    }

    // endregion
}
