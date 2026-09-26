package link.socket.ampere.eval.suite

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.domain.event.BenchEvent
import link.socket.ampere.eval.bench.BenchReport
import link.socket.ampere.eval.bench.RunMode
import link.socket.ampere.eval.meter.TraceConformanceMeter
import link.socket.ampere.eval.trace.Trace

/**
 * The regression gate: AMPERE's own eval suite, replayed against its committed golden traces
 * (AMPR-187 tasks 5.3 and 5.4).
 *
 * This is the test CI fails the build on. It runs in [RunMode.Replay] only — the harness is opened
 * without a live relay, so `Bench` would refuse Live mode even if something asked for it — and it
 * needs no API key, no network, and no writable directory beyond a temp one.
 *
 * When it goes red, read the failure message: it names each failing probe, its meter, and for a
 * conformance failure the first position where the run stopped matching its recording. If the
 * change was intended, re-record with `./gradlew :ampere-eval:recordGoldenTraces` and commit the
 * resulting diff — that diff is the record of what changed about the Arc.
 *
 * `runBlocking`, not `runTest`: the Arc path suspends across real dispatchers and real
 * `withTimeout` windows, and `runTest`'s virtual clock skips past those while an I/O hop is still
 * in flight.
 */
class AmpereEvalSuiteTest {

    @Test
    fun `the replay suite is green against its golden traces`() = runBlocking {
        EvalSuiteHarness.open().use { harness ->
            val report = harness.bench.run(AmpereEvalSuite.cases(GoldenTraces.loadAll()), RunMode.Replay).getOrThrow()

            assertEquals(AmpereEvalSuite.probes.size, report.results.size)
            assertTrue(report.results.all { it.passed }, report.explain())
            assertEquals(1.0, report.passRate)
        }
    }

    @Test
    fun `every probe's trace ends in the ArcSettled that describes its run`() = runBlocking {
        EvalSuiteHarness.open().use { harness ->
            val report = harness.bench.run(AmpereEvalSuite.cases(GoldenTraces.loadAll()), RunMode.Replay).getOrThrow()

            report.results.forEach { result ->
                val terminal = result.trace.events.lastOrNull()
                assertEquals(
                    BenchEvent.ArcSettled.EVENT_TYPE,
                    terminal?.type,
                    "Probe '${result.probeId}' recorded no terminal ArcSettled; " +
                        "trace was ${result.trace.events.map { it.type }}",
                )
            }
        }
    }

    /**
     * The other half of the `effect-free-under-destructive-seed` probe. A meter only ever sees a
     * trace, so the claim that a bench run performs no side effect has to be checked where the side
     * effect would land: the project directory the Arc was pointed at.
     */
    @Test
    fun `a full suite run leaves the project directory untouched`() = runBlocking {
        EvalSuiteHarness.open().use { harness ->
            val before = harness.projectDir.digest()

            harness.bench.run(AmpereEvalSuite.cases(GoldenTraces.loadAll()), RunMode.Replay).getOrThrow()

            assertEquals(before, harness.projectDir.digest(), "A bench run wrote to the project directory")
        }
    }

    /**
     * AMPR-187 task 5.4's validation, as a test rather than as a CI experiment: a deliberately
     * introduced regression turns the gate red.
     *
     * The regression is injected into the *recording* rather than into the runtime, which is the
     * same thing seen from the other side — a golden trace claiming the run marked one more goal
     * complete than it does is indistinguishable from a runtime that stopped marking one. Doing it
     * this way means the proof runs on every commit instead of living in a pull request nobody can
     * merge.
     */
    @Test
    fun `a regression against the golden trace turns the suite red`() = runBlocking {
        val probe = AmpereEvalSuite.probes.first()
        val golden = GoldenTraces.load(probe.id)
        val tampered = golden.withSettledField("completedGoalCount", JsonPrimitive(99))

        EvalSuiteHarness.open().use { harness ->
            val report = harness.bench.run(listOf(AmpereEvalSuite.case(probe, tampered)), RunMode.Replay).getOrThrow()

            val result = report.results.single()
            assertFalse(result.passed, "A trace that disagrees with the run was graded green")
            assertEquals(0.0, report.passRate)

            val conformance = result.readings.single { it.meterId == "matches-golden" }
            assertEquals(0.0, conformance.score)
            assertEquals(
                (golden.events.size - 1).toString(),
                conformance.detail["first_divergence_index"],
                "The tampered field is on the terminal event, so that is where conformance must break",
            )
        }
    }

    @Test
    fun `the suite holds between three and five probes and each says why it exists`() {
        assertTrue(
            AmpereEvalSuite.probes.size in 3..5,
            "AMPR-187 asks for 3-5 probes; found ${AmpereEvalSuite.probes.size}",
        )
        assertEquals(
            AmpereEvalSuite.probes.size,
            AmpereEvalSuite.probes.map { it.id }.toSet().size,
            "Probe ids must be unique — they name the golden trace files",
        )
        AmpereEvalSuite.probes.forEach { probe ->
            assertTrue(probe.why.isNotBlank(), "Probe '${probe.id}' has no stated reason")
            assertTrue(probe.userGoal.isNotBlank(), "Probe '${probe.id}' has no seed")
        }
    }

    /**
     * Canonicalization and conformance have to agree on what counts as volatile. A field one side
     * rewrites and the other compares would make the gate fail on every re-record; a field one side
     * ignores and the other pins would let a real change slip through canonicalized to a constant.
     */
    @Test
    fun `canonicalization and conformance agree on which fields are volatile`() {
        assertEquals(
            TraceConformanceMeter.DEFAULT_VOLATILE_FIELDS,
            GoldenTraces.CANONICALIZED_FIELDS,
        )
    }

    // region — helpers

    /** [BenchReport] as a failure message: every failing probe, meter, score and detail. */
    private fun BenchReport.explain(): String = buildString {
        appendLine("Eval suite failed (passRate=$passRate). Failing probes:")
        results.filterNot { it.passed }.forEach { result ->
            appendLine("  ${result.probeId}:")
            result.readings.forEach { reading ->
                appendLine("    [${reading.meterId}] score=${reading.score} passed=${reading.passed} ${reading.detail}")
            }
        }
        appendLine(
            "If the change was intended, re-record: ./gradlew :ampere-eval:recordGoldenTraces",
        )
    }

    /** This trace with [field] overwritten on its terminal `ArcSettled` payload. */
    private fun Trace.withSettledField(field: String, value: JsonPrimitive): Trace {
        val last = events.last()
        val payload = last.payload as JsonObject
        return copy(events = events.dropLast(1) + last.copy(payload = JsonObject(payload + (field to value))))
    }

    /** Every file under this directory, as relative path to content digest. */
    private fun Path.digest(): Map<String, String> = Files.walk(this).use { paths ->
        paths.asSequence()
            .filter { Files.isRegularFile(it) }
            .associate { file ->
                relativize(file).toString() to
                    MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(file))
                        .joinToString("") { byte -> byte.toInt().and(0xff).toString(16).padStart(2, '0') }
            }
    }

    // endregion
}
