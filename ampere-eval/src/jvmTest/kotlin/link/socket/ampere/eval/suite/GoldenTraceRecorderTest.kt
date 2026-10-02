package link.socket.ampere.eval.suite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.event.BenchEvent
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.eval.bench.EvalCase
import link.socket.ampere.eval.bench.EvalSeed
import link.socket.ampere.eval.bench.RunMode
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance

/**
 * The re-record tool (AMPR-187 task 5.5): drives every probe in [AmpereEvalSuite] once in
 * [RunMode.Live], canonicalizes each recorded trace, and writes it over the committed golden.
 *
 * ```
 * ./gradlew :ampere-eval:recordGoldenTraces
 * git diff --stat ampere-eval/src/jvmTest/resources/golden
 * ```
 *
 * An **empty** diff means the Arc behaves exactly as its recordings say — that is the literal proof
 * that replaying a golden trace reproduces its recorded outputs. A **non-empty** diff is a behavior
 * change: read it, convince yourself it is the change you meant to make, and commit it alongside
 * the code that caused it. The diff is the durable record of what changed, which is why the traces
 * are canonicalized rather than committed raw.
 *
 * It is a test rather than a `main` so it runs on the `jvmTest` classpath, which already carries the
 * JDBC driver a real event store and trace store need. It does nothing at all unless
 * [GoldenTraces.GOLDEN_DIR_PROPERTY] is set, which only the `recordGoldenTraces` task does — an
 * ordinary `jvmTest` run must never rewrite the traces it is checking against.
 *
 * Recording is a single bench run over the whole suite, in the same order the gate runs it, because
 * a recording made under a different rig than the gate uses is not a recording of the gate's run.
 */
class GoldenTraceRecorderTest {

    @Test
    fun `records a canonical golden trace for every probe`() = runBlocking {
        val outputDirectory = GoldenTraces.outputDirectory()
        if (outputDirectory == null) {
            println(
                "[golden-trace-recorder] skipped: -D${GoldenTraces.GOLDEN_DIR_PROPERTY} is not set. " +
                    "Run ./gradlew :ampere-eval:recordGoldenTraces to re-record.",
            )
            return@runBlocking
        }

        EvalSuiteHarness.open(live = true).use { harness ->
            val report = harness.bench.run(AmpereEvalSuite.probes.map(::recordingCase), RunMode.Live).getOrThrow()

            assertEquals(AmpereEvalSuite.probes.size, report.results.size)

            report.results.forEach { result ->
                val trace = result.trace
                assertTrue(
                    trace.events.isNotEmpty(),
                    "Probe '${result.probeId}' recorded an empty trace; committing one would make " +
                        "every meter fail on an empty reference",
                )
                assertEquals(
                    BenchEvent.ArcSettled.EVENT_TYPE,
                    trace.events.last().type,
                    "Probe '${result.probeId}' recorded no terminal ArcSettled",
                )

                GoldenTraces.write(outputDirectory, result.probeId, GoldenTraces.canonicalize(result.probeId, trace))

                println(
                    "[golden-trace-recorder] ${result.probeId}: ${trace.size} event(s) -> " +
                        trace.events.joinToString(", ") { it.type } + "\n" +
                        "    " + trace.events.last().payload,
                )
            }

            println("[golden-trace-recorder] wrote ${report.results.size} trace(s) to $outputDirectory")
        }
    }

    /**
     * The same, for the Rung 0 routing bench (AMPR-225): every scenario in [Rung0RoutingSuite]
     * run once through [Rung0RoutingBench], canonicalized, and written over its committed golden.
     * There is no Live/Replay distinction here — the relay is the deterministic thing — so the
     * recording run is the gate's run with the meters swapped for a trivial one.
     */
    @Test
    fun `records a canonical golden trace for every Rung 0 routing scenario`() = runBlocking {
        val outputDirectory = GoldenTraces.outputDirectory()
        if (outputDirectory == null) {
            println(
                "[golden-trace-recorder] skipped: -D${GoldenTraces.GOLDEN_DIR_PROPERTY} is not set. " +
                    "Run ./gradlew :ampere-eval:recordGoldenTraces to re-record.",
            )
            return@runBlocking
        }

        Rung0RoutingBench.open().use { bench ->
            val results = bench.run(Rung0RoutingSuite.scenarios.map(::recordingCase))

            assertEquals(Rung0RoutingSuite.scenarios.size, results.size)

            results.forEach { result ->
                val trace = result.trace
                assertTrue(
                    trace.events.isNotEmpty(),
                    "Scenario '${result.caseId}' recorded no routing event; committing an empty " +
                        "trace would make every meter fail on an empty reference",
                )
                assertEquals(
                    RoutingEvent.RouteResolved.EVENT_TYPE,
                    trace.events.last().type,
                    "Scenario '${result.caseId}' recorded no terminal RouteResolved",
                )

                GoldenTraces.write(outputDirectory, result.caseId, GoldenTraces.canonicalize(result.caseId, trace))

                println(
                    "[golden-trace-recorder] ${result.caseId}: ${trace.size} event(s) -> " +
                        trace.events.joinToString(", ") { it.type } + "\n" +
                        "    " + trace.events.last().payload,
                )
            }

            println("[golden-trace-recorder] wrote ${results.size} routing trace(s) to $outputDirectory")
        }
    }

    /**
     * A probe as a [RunMode.Live] case.
     *
     * The meters are deliberately trivial: a recording run is not a graded run, and grading it
     * against the probe's real meters would need the golden trace this run exists to produce. No
     * `goldenTrace` either — Live mode drives the real relay and has nothing to replay.
     */
    private fun recordingCase(probe: AmpereEvalSuite.ProbeSpec): EvalCase = EvalCase(
        id = probe.id,
        arcId = probe.arcId,
        seed = EvalSeed(userGoal = probe.userGoal),
        meters = listOf(Meter { Result.success(Reading(score = 1.0, passed = true, meterId = "recording")) }),
        tolerance = Tolerance(minScore = 0.0),
        goldenTrace = null,
    )

    /** A routing scenario with a trivial meter, for the same reason [recordingCase] has one. */
    private fun recordingCase(scenario: Rung0RoutingSuite.ScenarioSpec): RoutingCase = RoutingCase(
        id = scenario.id,
        context = scenario.context,
        meters = listOf(Meter { Result.success(Reading(score = 1.0, passed = true, meterId = "recording")) }),
        tolerance = Tolerance(minScore = 0.0),
    )
}
