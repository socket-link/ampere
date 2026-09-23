package link.socket.ampere.eval.bench

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.ArcRunEvent
import link.socket.ampere.agents.domain.event.BenchEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database
import link.socket.ampere.domain.arc.ArcPhase
import link.socket.ampere.domain.arc.TerminationReason
import link.socket.ampere.eval.db.EvalDatabase
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance
import link.socket.ampere.eval.relay.MissPolicy
import link.socket.ampere.eval.relay.PlaybackRelay
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceRecorder
import link.socket.ampere.eval.trace.TraceService
import link.socket.ampere.time.MutableClock
import okio.Path.Companion.toPath

/**
 * AMPR-186 tasks 4.3 and 4.5 validation.
 *
 * F1a (AMPR-337): [Bench] publishes through an [AgentEventApi] door, so each test builds one over
 * an in-memory `EventStore` and asserts the store sees what the bus sees.
 */
class BenchTest {

    /**
     * A door over a fresh in-memory database. Its bus dispatches inline, so observers see events in
     * order, unless [openDoor] is handed another scope.
     */
    private class Door(val api: AgentEventApi, val repository: EventRepository, val bus: EventSerialBus)

    private fun openDoor(
        clock: Clock = Clock.System,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): Door {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseSchemaManager.ensure(driver).getOrThrow()
        val bus = EventSerialBus(scope = scope)
        val repository = EventRepository(DEFAULT_JSON, scope, Database(driver))
        val api = AgentEventApi(
            agentId = "bench",
            eventRepository = repository,
            eventSerialBus = bus,
            clock = clock,
        )
        return Door(api, repository, bus)
    }

    @Test
    fun `2-probe suite runs green and deterministic in Replay mode`() = runTest {
        val door = openDoor()
        val observed = mutableListOf<BenchEvent>()
        subscribeToBenchEvents(door.bus) { observed.add(it) }

        val bench = Bench(
            projectDir = testProjectDir(),
            eventApi = door.api,
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
    fun `a Replay run persists every BenchEvent under its run id`() = runTest {
        val door = openDoor()
        val bench = Bench(
            projectDir = testProjectDir(),
            eventApi = door.api,
            maxFlowTicks = 1,
        )

        val suite = listOf(
            probe(id = "probe-1", arcId = "startup-saas", tolerance = Tolerance(minScore = 0.5)),
            probe(id = "probe-2", arcId = "devops-pipeline", tolerance = Tolerance(minScore = 0.5)),
        )
        bench.run(suite, RunMode.Replay).getOrThrow()

        val graded = door.repository.getEventsByType(BenchEvent.ProbeGraded.EVENT_TYPE).getOrThrow()
        assertEquals(2, graded.size)
        assertEquals(setOf("probe-1", "probe-2"), graded.map { (it as BenchEvent.ProbeGraded).probeId }.toSet())

        assertEquals(1, door.repository.getEventsByType(BenchEvent.BenchRunStarted.EVENT_TYPE).getOrThrow().size)
        assertEquals(1, door.repository.getEventsByType(BenchEvent.BenchRunCompleted.EVENT_TYPE).getOrThrow().size)

        val benchRunId = (graded.first() as BenchEvent.ProbeGraded).runId
        val stored = door.repository.getEventsSinceSequence(0).getOrThrow().filter { it.event is BenchEvent }
        assertEquals(4, stored.size)
        assertTrue(stored.all { it.runId == benchRunId })
    }

    @Test
    fun `tightening a probe's Tolerance flips it red deterministically`() = runTest {
        val door = openDoor()
        val bench = Bench(
            projectDir = testProjectDir(),
            eventApi = door.api,
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

    @Test
    fun `an injected clock stamps every BenchEvent with its time`() = runTest {
        val fixed = Instant.parse("2026-01-01T00:00:00Z")
        val door = openDoor(clock = MutableClock(fixed))
        val observed = mutableListOf<BenchEvent>()
        subscribeToBenchEvents(door.bus) { observed.add(it) }

        val bench = Bench(
            projectDir = testProjectDir(),
            eventApi = door.api,
            maxFlowTicks = 1,
        )

        val suite = listOf(
            probe(id = "probe-1", arcId = "startup-saas", tolerance = Tolerance(minScore = 0.5)),
            probe(id = "probe-2", arcId = "devops-pipeline", tolerance = Tolerance(minScore = 0.5)),
        )
        bench.run(suite, RunMode.Replay).getOrThrow()

        val graded = observed.filterIsInstance<BenchEvent.ProbeGraded>()
        assertEquals(2, graded.size)
        assertEquals(listOf(fixed, fixed), graded.map { it.timestamp })
        assertTrue(observed.all { it.timestamp == fixed })

        // The door's recorded_at comes from the same clock the bench defaulted to.
        val stored = door.repository.getEventsSinceSequence(0).getOrThrow().filter { it.event is BenchEvent }
        assertTrue(stored.all { it.recordedAt == fixed })
    }

    /**
     * AMPR-359: a live case whose Arc fails leaves its completion manifest in the trace recorded
     * for it, which is what outlives the bench.
     *
     * The door's bus dispatches onto a scheduler nobody advances, so bus delivery can never reach
     * the recorder: the manifest gets into the trace through the recording itself, or not at all.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `a failed live case records its completion manifest in the case's trace`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { EvalDatabase.Schema.create(it) }
        try {
            val door = openDoor(scope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler())))
            val traceService = TraceService(EvalDatabase(driver))
            val bench = Bench(
                // No README or AGENTS.md, so Charge refuses the project and the run fails.
                projectDir = createTempDirectory("bench-live-fails").toString().toPath(),
                eventApi = door.api,
                liveRelay = PlaybackRelay(trace = emptyTrace(), missPolicy = MissPolicy.Error),
                traceRecorder = TraceRecorder(door.bus, traceService),
                liveModeEnabled = true,
                maxFlowTicks = 1,
            )

            val suite = listOf(probe(id = "probe-live", arcId = "startup-saas", tolerance = Tolerance(minScore = 0.5)))
            val result = bench.run(suite, RunMode.Live).getOrThrow().results.single()

            assertFalse(result.passed)
            val recorded = result.trace.events.single { it.type == ArcRunEvent.CompletionManifestRecorded.EVENT_TYPE }
            val manifest = assertIs<ArcRunEvent.CompletionManifestRecorded>(
                DEFAULT_JSON.decodeFromJsonElement(Event.serializer(), recorded.payload),
            ).record
            assertEquals(TerminationReason.ERROR, manifest.endedBy)
            assertEquals(ArcPhase.CHARGE, manifest.endedDuring)
            assertNull(manifest.unmetGoals, "Charge never built a goal tree, so what was left undone is unknown")

            // Durable: the persisted trace carries it too.
            assertEquals(result.trace, traceService.load(result.trace.id).getOrThrow())

            // And the door stored it, under the Arc run's own id rather than the bench run's.
            val stored = door.repository.getEventsSinceSequence(0).getOrThrow().single { it.event is ArcRunEvent }
            assertEquals(manifest.runId, stored.runId)
            assertEquals(manifest, (stored.event as ArcRunEvent.CompletionManifestRecorded).record)
        } finally {
            driver.close()
        }
    }

    private fun emptyTrace(): Trace =
        Trace(id = "t-empty", runId = "r-empty", arcId = "startup-saas", createdAt = 0L, events = emptyList())

    private fun subscribeToBenchEvents(bus: EventSerialBus, onEvent: (BenchEvent) -> Unit) {
        val handler = EventHandler<Event, Subscription> { event, _ -> onEvent(event as BenchEvent) }
        bus.subscribe("bench-test-observer", BenchEvent.BenchRunStarted.EVENT_TYPE, handler)
        bus.subscribe("bench-test-observer", BenchEvent.ProbeGraded.EVENT_TYPE, handler)
        bus.subscribe("bench-test-observer", BenchEvent.BenchRunCompleted.EVENT_TYPE, handler)
    }

    private fun probe(id: String, arcId: String, tolerance: Tolerance): EvalCase {
        val trace = Trace(id = "t-$id", runId = "r-$id", arcId = arcId, createdAt = 0L, events = emptyList())
        val meter = Meter { _ -> Result.success(Reading(score = 1.0, passed = true, meterId = "always-pass")) }
        return EvalCase(
            id = id,
            arcId = arcId,
            seed = EvalSeed(userGoal = "Implement a small feature"),
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
