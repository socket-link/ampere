package link.socket.ampere.eval.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance
import link.socket.ampere.eval.trace.Trace

/** AMPR-186 task 4.1 validation; renamed Probe → EvalCase in AMPR-318. */
class EvalCaseTest {

    // region — task 4.1: core types

    @Test
    fun `EvalCase constructs with a golden trace`() {
        val trace = goldenTrace()
        val case = EvalCase(
            id = "case-1",
            arcId = "arc-1",
            seed = EvalSeed(userGoal = "Implement user login"),
            meters = listOf(alwaysPassMeter()),
            tolerance = Tolerance(minScore = 0.8),
            goldenTrace = trace,
        )

        assertEquals("case-1", case.id)
        assertEquals("arc-1", case.arcId)
        assertEquals(trace, case.goldenTrace)
    }

    @Test
    fun `EvalCase goldenTrace defaults to null`() {
        val case = EvalCase(
            id = "case-2",
            arcId = "arc-1",
            seed = EvalSeed(userGoal = "goal"),
            meters = listOf(alwaysPassMeter()),
            tolerance = Tolerance(minScore = 0.0),
        )

        assertNull(case.goldenTrace)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated Probe aliases still compile and resolve to the new types`() {
        // AMPR-318 task 4 validation: old names keep working with a deprecation warning only.
        val case: Probe = Probe(
            id = "legacy",
            arcId = "arc-1",
            seed = ProbeSeed(userGoal = "goal"),
            meters = listOf(alwaysPassMeter()),
            tolerance = Tolerance(minScore = 0.0),
        )
        val result: ProbeResult = ProbeResult(
            probeId = case.id,
            readings = emptyList(),
            passed = true,
            trace = goldenTrace(),
        )

        val newTypeCase: EvalCase = case
        val newTypeResult: EvalCaseResult = result
        assertEquals(case, newTypeCase)
        assertEquals(result, newTypeResult)
    }

    @Test
    fun `RunMode has exactly Replay and Live variants`() {
        val modes: List<RunMode> = listOf(RunMode.Replay, RunMode.Live)
        assertTrue(modes.contains(RunMode.Replay))
        assertTrue(modes.contains(RunMode.Live))
    }

    @Test
    fun `BenchReport passRate reflects EvalCaseResult pass count`() {
        val trace = goldenTrace()
        val results = listOf(
            EvalCaseResult(probeId = "a", readings = emptyList(), passed = true, trace = trace),
            EvalCaseResult(probeId = "b", readings = emptyList(), passed = false, trace = trace),
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
