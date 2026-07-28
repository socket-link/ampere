package link.socket.ampere.eval.bench

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.event.BenchEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance
import link.socket.ampere.eval.trace.Trace
import okio.Path.Companion.toPath

/** AMPR-186 tasks 4.3 and 4.5 validation. */
class BenchTest {

    @Test
    fun `2-probe suite runs green and deterministic in Replay mode`() = runTest {
        val bus = EventSerialBus(scope = CoroutineScope(Dispatchers.Unconfined))
        val observed = mutableListOf<BenchEvent>()
        subscribeToBenchEvents(bus) { observed.add(it) }

        val bench = Bench(
            projectDir = testProjectDir(),
            eventBus = bus,
            maxFlowTicks = 1,
        )

        val suite = listOf(
            probe(id = "probe-1", arcId = "startup-saas", tolerance = Tolerance(minScore = 0.5)),
            probe(id = "probe-2", arcId = "devops-pipeline", tolerance = Tolerance(minScore = 0.5)),
        )

        val report = bench.run(suite, RunMode.Replay).getOrThrow()

        assertEquals(2, report.results.size)
        assertEquals(1.0, report.passRate)
        assertTrue(report.results.all { it.passed })

        assertTrue(observed.any { it is BenchEvent.BenchRunStarted })
        assertEquals(2, observed.count { it is BenchEvent.ProbeGraded })
        assertTrue(observed.any { it is BenchEvent.BenchRunCompleted })
    }

    @Test
    fun `tightening a probe's Tolerance flips it red deterministically`() = runTest {
        val bus = EventSerialBus(scope = CoroutineScope(Dispatchers.Unconfined))
        val bench = Bench(
            projectDir = testProjectDir(),
            eventBus = bus,
            maxFlowTicks = 1,
        )

        val goodSuite = listOf(probe(id = "probe-1", arcId = "startup-saas", tolerance = Tolerance(minScore = 0.5)))
        val greenReport = bench.run(goodSuite, RunMode.Replay).getOrThrow()
        assertTrue(greenReport.results.single().passed)

        val strictSuite = listOf(probe(id = "probe-1", arcId = "startup-saas", tolerance = Tolerance(minScore = 1.1)))
        val redReport = bench.run(strictSuite, RunMode.Replay).getOrThrow()
        assertTrue(!redReport.results.single().passed)
        assertTrue(redReport.passRate < 1.0)
    }

    private fun subscribeToBenchEvents(bus: EventSerialBus, onEvent: (BenchEvent) -> Unit) {
        val handler = EventHandler<Event, Subscription> { event, _ -> onEvent(event as BenchEvent) }
        bus.subscribe("bench-test-observer", BenchEvent.BenchRunStarted.EVENT_TYPE, handler)
        bus.subscribe("bench-test-observer", BenchEvent.ProbeGraded.EVENT_TYPE, handler)
        bus.subscribe("bench-test-observer", BenchEvent.BenchRunCompleted.EVENT_TYPE, handler)
    }

    private fun probe(id: String, arcId: String, tolerance: Tolerance): Probe {
        val trace = Trace(id = "t-$id", runId = "r-$id", arcId = arcId, createdAt = 0L, events = emptyList())
        val meter = Meter { _ -> Result.success(Reading(score = 1.0, passed = true, meterId = "always-pass")) }
        return Probe(
            id = id,
            arcId = arcId,
            seed = ProbeSeed(userGoal = "Implement a small feature"),
            meters = listOf(meter),
            tolerance = tolerance,
            goldenTrace = trace,
        )
    }

    private fun testProjectDir(): okio.Path {
        val tempDir = createTempDirectory("bench-test")
        tempDir.resolve("README.md").writeText("# BenchTestProject\n\nA test project for Bench integration.")
        tempDir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Kotlin

            ## Conventions
            - Use suspend functions

            ## Architecture
            - Clean architecture
            """.trimIndent(),
        )
        return tempDir.toString().toPath()
    }
}
