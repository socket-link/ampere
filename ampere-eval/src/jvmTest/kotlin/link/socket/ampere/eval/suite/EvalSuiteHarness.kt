package link.socket.ampere.eval.suite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.Closeable
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.routing.CapabilityRoutingDefaults
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database
import link.socket.ampere.eval.bench.Bench
import link.socket.ampere.eval.db.EvalDatabase
import link.socket.ampere.eval.trace.TraceRecorder
import link.socket.ampere.eval.trace.TraceService
import link.socket.ampere.time.MutableClock
import okio.Path.Companion.toPath

/**
 * Everything the Ampere-first eval suite needs to run one [Bench], assembled so that both the
 * Replay gate ([AmpereEvalSuiteTest]) and the re-recorder ([GoldenTraceRecorderTest]) drive an
 * identical rig. A golden trace recorded through one has to be reproducible through the other, so
 * the two cannot be allowed to differ in their door, their clock, or their project.
 *
 * ### What is held fixed, and why
 *
 * - **The clock** is a [MutableClock] pinned at [FIXED_INSTANT]. `AmpereRuntime` reads time from
 *   the clock it is handed (AMPR-335), so pinning it takes wall-clock out of the recording.
 * - **The bus dispatches inline** (`Dispatchers.Unconfined`), so a case's `ProbeGraded` is
 *   delivered before the next case's recording window opens. On a dispatcher that queues, a
 *   previous case's event could land inside the next case's trace and the suite would be
 *   flaky by construction.
 * - **The project is a fixture**, generated from [FIXTURE_README]/[FIXTURE_AGENTS] into a temp
 *   directory rather than pointed at the repository root. Charge reads the project's `README.md`
 *   and `AGENTS.md` and refuses a context it cannot parse, so aiming the suite at the live repo
 *   would let an edit to either file turn the gate red without any Arc behavior having changed.
 * - **The tick budget is [MAX_FLOW_TICKS]**, which is what makes the suite finish in
 *   milliseconds and what puts [AmpereEvalSuite]'s tick-budget probe on its edge.
 *
 * The fixture's temp path never reaches a recorded trace: nothing the Arc publishes on the happy
 * path carries the project directory, and `BenchEvent.ArcSettled` is counts and enums only.
 */
internal class EvalSuiteHarness private constructor(
    val bench: Bench,
    val bus: EventSerialBus,
    val repository: EventRepository,
    val projectDir: java.nio.file.Path,
    private val eventDriver: JdbcSqliteDriver,
    private val evalDriver: JdbcSqliteDriver,
) : Closeable {

    override fun close() {
        eventDriver.close()
        evalDriver.close()
    }

    companion object {
        /** The instant every clock in the suite is pinned to. */
        val FIXED_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")

        /**
         * Flow ticks a suite case is allowed. One tick is a deliberate choice, not a shortcut:
         * it is the smallest budget that still runs every agent in the Arc's order exactly once,
         * so a probe measures one full pass of the pipeline and nothing else.
         */
        const val MAX_FLOW_TICKS: Int = 1

        /**
         * Opens a harness. [live] wires the relay and recorder a [link.socket.ampere.eval.bench.RunMode.Live]
         * recording needs and opts Live mode in; the Replay gate leaves it `false`, which is what
         * keeps Live mode structurally out of CI.
         */
        fun open(live: Boolean = false): EvalSuiteHarness {
            val eventDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            DatabaseSchemaManager.ensure(eventDriver).getOrThrow()
            val evalDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { EvalDatabase.Schema.create(it) }

            val clock = MutableClock(FIXED_INSTANT)
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val bus = EventSerialBus(scope = scope)
            val repository = EventRepository(DEFAULT_JSON, scope, Database(eventDriver))
            val eventApi = AgentEventApi(
                agentId = "ampere-eval-suite",
                eventRepository = repository,
                eventSerialBus = bus,
                clock = clock,
            )

            val recorder = TraceRecorder(
                bus = bus,
                traceService = TraceService(EvalDatabase(evalDriver)),
                // Pinned for the same reason the run's clock is: `Trace.createdAt` would
                // otherwise be the one wall-clock field in a committed golden trace.
                clockMillis = { FIXED_INSTANT.toEpochMilliseconds() },
            )

            val projectDir = fixtureProject()

            return EvalSuiteHarness(
                bench = Bench(
                    projectDir = projectDir.toString().toPath(),
                    eventApi = eventApi,
                    liveRelay = if (live) liveRelay() else null,
                    traceRecorder = recorder,
                    liveModeEnabled = live,
                    maxFlowTicks = MAX_FLOW_TICKS,
                ),
                bus = bus,
                repository = repository,
                projectDir = projectDir,
                eventDriver = eventDriver,
                evalDriver = evalDriver,
            )
        }

        /**
         * The relay a Live recording runs against: the real [CognitiveRelayImpl] over the bundled
         * model catalog, built exactly as `SparkAgentFactory` builds its floor-enforcing one.
         *
         * Routing is a local decision — it selects an `AIConfiguration` and calls no provider — so
         * this needs no API key. If the Arc path ever does start invoking a model, a re-record will
         * say so loudly rather than quietly replaying a stub.
         */
        private fun liveRelay(): CognitiveRelay = CognitiveRelayImpl(
            initialConfig = RelayConfig(rules = CapabilityRoutingDefaults.defaultCapabilityRules()),
            registry = InMemoryModelDescriptorRegistry(),
        )

        private fun fixtureProject(): java.nio.file.Path {
            val dir = createTempDirectory("ampere-eval-suite")
            dir.resolve("README.md").writeText(FIXTURE_README)
            dir.resolve("AGENTS.md").writeText(FIXTURE_AGENTS)
            return dir
        }

        /**
         * The fixture project's `README.md`. Charge's extractor takes the project id from the
         * first heading and the description from the prose under it.
         */
        private val FIXTURE_README: String = """
            # AmpereEvalFixture

            A fixed, minimal project the Ampere-first eval suite runs its Arcs against.

            Edit this only when a probe's golden trace is meant to change — it is a suite input,
            and changing it is a behavior change as surely as changing the Arc would be.
        """.trimIndent() + "\n"

        /**
         * The fixture project's `AGENTS.md`. The three headings are load-bearing: Charge's
         * `isValidContext` refuses a context with no tech stack, no conventions, or no
         * architecture, and an Arc that Charge refuses never reaches Flow.
         */
        private val FIXTURE_AGENTS: String = """
            # AGENTS

            ## Dependencies
            - Kotlin
            - kotlinx.coroutines

            ## Conventions
            - Suspend functions at every I/O boundary
            - Result at every fallible boundary

            ## Architecture
            - Clean architecture, domain types free of platform dependencies
        """.trimIndent() + "\n"
    }
}
