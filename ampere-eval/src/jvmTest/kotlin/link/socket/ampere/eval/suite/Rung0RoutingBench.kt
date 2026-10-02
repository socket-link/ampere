package link.socket.ampere.eval.suite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.domain.routing.CapabilityRoutingDefaults
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingResolution
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.routingEventSink
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.eval.db.EvalDatabase
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceRecorder
import link.socket.ampere.eval.trace.TraceService
import link.socket.ampere.time.MutableClock

/**
 * One routing scenario to run and grade: the [context] the relay resolves, and the meters that
 * grade the events it publishes while doing so.
 */
internal data class RoutingCase(
    val id: String,
    val context: RoutingContext,
    val meters: List<Meter>,
    val tolerance: Tolerance,
)

/** How one [RoutingCase] went: what the relay resolved, what it published, and how that graded. */
internal data class RoutingCaseResult(
    val caseId: String,
    val resolution: RoutingResolution,
    val readings: List<Reading>,
    val passed: Boolean,
    val trace: Trace,
)

/**
 * The rig the Rung 0 routing bench runs on (AMPR-225): the real `CognitiveRelayImpl` over the
 * bundled catalog and the default capability rules, publishing through a door onto a bus a
 * [TraceRecorder] is listening to. Each case is one `resolveWithMetadata` inside its own recording
 * window; the recording is what the case's meters grade.
 *
 * The gate ([Rung0RoutingSuiteTest]) and the re-recorder ([GoldenTraceRecorderTest]) both open this
 * and nothing else, for the reason [EvalSuiteHarness] gives: a golden trace recorded through one
 * rig has to be reproducible through the other.
 *
 * ### What is held fixed
 *
 * - **The relay** is built exactly as [EvalSuiteHarness] builds its live one — the same rules and
 *   the same seeded registry a production `SparkAgentFactory` gets.
 * - **The bus dispatches inline** (`Dispatchers.Unconfined`), so every event the relay publishes
 *   lands in the recording before `resolveWithMetadata` returns and the window closes.
 * - **The door's clock** is pinned to [EvalSuiteHarness.FIXED_INSTANT]. The relay stamps its own
 *   events from the system clock, which canonicalization replaces; the door's `recorded_at` is the
 *   other time that would otherwise differ between runs.
 * - **The fallback configuration** is a metered cloud model. Every scenario matches a capability
 *   rule, so it is never returned; a scenario that *did* fall through to it would resolve with
 *   reason `default` and fail its selection meter, which is the intended tripwire.
 */
internal class Rung0RoutingBench private constructor(
    private val door: InMemoryEventDoor.Handle,
    private val recorder: TraceRecorder,
    private val relay: CognitiveRelay,
    private val evalDriver: JdbcSqliteDriver,
) : Closeable {

    /** Runs every case in order, each in its own recording window, and grades each recording. */
    suspend fun run(cases: List<RoutingCase>): List<RoutingCaseResult> = cases.map { case -> runCase(case) }

    private suspend fun runCase(case: RoutingCase): RoutingCaseResult {
        val runId = runId(case)
        val handle = recorder.start(runId = runId, arcId = Rung0RoutingSuite.ARC_ID)

        var traceResult: Result<Trace>? = null
        val resolution = try {
            relay.resolveWithMetadata(
                // The sink stores the context's workflow id as the envelope's run id, which is
                // how the door files these events under the same run the recording names.
                context = case.context.copy(workflowId = runId),
                fallbackConfiguration = UNROUTED_FALLBACK,
            )
        } finally {
            traceResult = withContext(NonCancellable) { handle.stop() }
        }
        val trace = checkNotNull(traceResult) { "TraceRecorder handle was not stopped." }.getOrThrow()

        val readings = case.meters.map { meter ->
            meter.measure(trace).getOrElse { error ->
                Reading(
                    score = 0.0,
                    passed = false,
                    meterId = "error",
                    detail = mapOf("error" to (error.message ?: "unknown")),
                )
            }
        }
        val passed = readings.isNotEmpty() && readings.all { case.tolerance.passes(it.score) }
        return RoutingCaseResult(
            caseId = case.id,
            resolution = resolution,
            readings = readings,
            passed = passed,
            trace = trace,
        )
    }

    /** Derived from the case id so a recorded trace names the scenario it belongs to. */
    private fun runId(case: RoutingCase): String = "rung-0-routing/${case.id}"

    override fun close() {
        door.close()
        evalDriver.close()
    }

    companion object {
        /**
         * Returned only if no rule matches, which no scenario in [Rung0RoutingSuite] allows. A
         * metered cloud model on purpose: were it on-device, a silent fall-through would look like
         * the floor winning.
         */
        val UNROUTED_FALLBACK: AIConfiguration =
            AIConfiguration_Default(provider = AIProvider_Anthropic, model = AIModel_Claude.Sonnet_5)

        fun open(): Rung0RoutingBench {
            val scope = CoroutineScope(Dispatchers.Unconfined)
            val door = InMemoryEventDoor.open(
                agentId = Rung0RoutingSuite.AGENT_ID,
                scope = scope,
                clock = MutableClock(EvalSuiteHarness.FIXED_INSTANT),
            )
            val evalDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { EvalDatabase.Schema.create(it) }
            val recorder = TraceRecorder(
                bus = door.bus,
                traceService = TraceService(EvalDatabase(evalDriver)),
                clockMillis = { EvalSuiteHarness.FIXED_INSTANT.toEpochMilliseconds() },
            )
            val relay = CognitiveRelayImpl(
                initialConfig = RelayConfig(rules = CapabilityRoutingDefaults.defaultCapabilityRules()),
                publish = door.api.routingEventSink(),
                registry = InMemoryModelDescriptorRegistry(),
            )
            return Rung0RoutingBench(door = door, recorder = recorder, relay = relay, evalDriver = evalDriver)
        }
    }
}
