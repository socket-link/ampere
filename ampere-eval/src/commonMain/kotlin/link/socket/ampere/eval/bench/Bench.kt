package link.socket.ampere.eval.bench

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.BenchEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.NoOpExecutor
import link.socket.ampere.domain.arc.AmpereRuntime
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ArcOutcome
import link.socket.ampere.domain.arc.ArcRegistry
import link.socket.ampere.domain.arc.CompletionManifest
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.relay.MissPolicy
import link.socket.ampere.eval.relay.PlaybackRelay
import link.socket.ampere.eval.trace.RecordingHandle
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceRecorder
import okio.Path

/**
 * Runs a suite of [EvalCase]s against an Arc, in either [RunMode.Replay] (a golden [Trace] +
 * `PlaybackRelay`, deterministic and CI-safe) or [RunMode.Live] (the real relay, recording a
 * fresh trace via [TraceRecorder]; nightly/on-demand only).
 *
 * Arcs run with tools in effect-free mode ([NoOpExecutor]) for every case, regardless of
 * [RunMode] — a bench run never performs a real tool side effect.
 *
 * @param eventApi the door every [BenchEvent] is published through: persisted to the
 *   `EventStore` under the bench run's id, then dispatched on the bus (F1). A Live-mode
 *   [TraceRecorder] should subscribe on `eventApi.eventSerialBus`, so the store and the trace
 *   see the same stream. A failed publish fails the run rather than being dropped.
 * @param liveModeEnabled explicit opt-in flag for [RunMode.Live] (AMPR-186 task 4.4); defaults
 *   `false` so [RunMode.Live] is refused unless a caller deliberately enables it. CI wiring
 *   never sets this, which is what keeps Live mode out of CI (ticket 5, out of scope here).
 * @param clock Stamps this bench's own [BenchEvent]s and is injected into every Arc it runs
 *   (AMPR-335), so a fixed clock makes a run deterministic in time as well as in scheduling.
 *   Defaults to the door's clock, so the events and their `recorded_at` agree.
 */
class Bench(
    private val projectDir: Path,
    private val eventApi: AgentEventApi,
    private val liveRelay: CognitiveRelay? = null,
    private val traceRecorder: TraceRecorder? = null,
    private val liveModeEnabled: Boolean = false,
    private val source: EventSource = EventSource.Human,
    private val maxFlowTicks: Int = 100,
    private val clock: Clock = eventApi.clock,
) {

    suspend fun run(suite: List<EvalCase>, mode: RunMode): Result<BenchReport> {
        if (mode is RunMode.Live && !liveModeEnabled) {
            return Result.failure(
                IllegalStateException("RunMode.Live requires Bench to be constructed with liveModeEnabled = true."),
            )
        }

        val runId = generateUUID("bench-run")
        eventApi.publish(
            BenchEvent.BenchRunStarted(
                eventId = generateUUID("bench-started", runId),
                runId = runId,
                eventSource = source,
                timestamp = clock.now(),
                mode = mode.toString(),
                probeCount = suite.size,
            ),
            runId = runId,
        ).getOrElse { return Result.failure(it) }

        val results = suite.map { case ->
            val result = runCase(runId, case, mode)
            eventApi.publish(
                BenchEvent.ProbeGraded(
                    eventId = generateUUID("probe-graded", runId, case.id),
                    runId = runId,
                    eventSource = source,
                    timestamp = clock.now(),
                    probeId = case.id,
                    passed = result.passed,
                    meanScore = result.readings.map { it.score }.average().takeUnless { it.isNaN() } ?: 0.0,
                ),
                runId = runId,
            ).getOrElse { return Result.failure(it) }
            result
        }

        val passRate = if (results.isEmpty()) 0.0 else results.count { it.passed }.toDouble() / results.size
        val report = BenchReport(results = results, passRate = passRate)

        eventApi.publish(
            BenchEvent.BenchRunCompleted(
                eventId = generateUUID("bench-completed", runId),
                runId = runId,
                eventSource = source,
                timestamp = clock.now(),
                passRate = report.passRate,
                probeCount = results.size,
            ),
            runId = runId,
        ).getOrElse { return Result.failure(it) }

        return Result.success(report)
    }

    /**
     * Runs and grades a single case. A case-level failure (unresolved arc, missing golden
     * trace, `PlaybackMiss` divergence, or a runtime exception) degrades to a failing
     * [EvalCaseResult] rather than aborting the whole suite, so one case's divergence doesn't
     * blank out the report (AMPR-186 task 4.5: "report aggregates all probes").
     */
    private suspend fun runCase(runId: String, case: EvalCase, mode: RunMode): EvalCaseResult {
        val arcConfig = ArcRegistry.get(case.arcId)
            ?: return failingResult(
                case,
                emptyTrace(runId, case),
                "No ArcConfig registered for arcId '${case.arcId}'.",
            )

        return when (mode) {
            RunMode.Replay -> runReplay(runId, case, arcConfig)
            RunMode.Live -> runLive(runId, case, arcConfig)
        }
    }

    private suspend fun runReplay(runId: String, case: EvalCase, arcConfig: ArcConfig): EvalCaseResult {
        val goldenTrace = case.goldenTrace
            ?: return failingResult(
                case,
                emptyTrace(runId, case),
                "EvalCase '${case.id}' has no goldenTrace for Replay mode.",
            )

        val playbackRelay = PlaybackRelay(trace = goldenTrace, missPolicy = MissPolicy.Error)

        val outcome = runArc(arcConfig, case, playbackRelay)

        return when (outcome) {
            is ArcOutcome.Completed -> grade(case, goldenTrace)
            is ArcOutcome.Cancelled ->
                failingResult(case, goldenTrace, "Arc run was cancelled before it finished.")
            is ArcOutcome.Failed ->
                failingResult(case, goldenTrace, "Arc run diverged from goldenTrace: ${outcome.cause.message}")
        }
    }

    private suspend fun runLive(runId: String, case: EvalCase, arcConfig: ArcConfig): EvalCaseResult {
        val relay = liveRelay
            ?: return failingResult(
                case,
                emptyTrace(runId, case),
                "RunMode.Live requires a liveRelay to be configured on Bench.",
            )
        val recorder = traceRecorder
            ?: return failingResult(
                case,
                emptyTrace(runId, case),
                "RunMode.Live requires a traceRecorder to be configured on Bench.",
            )

        val handle = recorder.start(runId = runId, arcId = case.arcId)

        // The recording handle must be closed even if the bench coroutine is cancelled, so stop
        // it in a `finally` rather than only on the happy path. A run that is cut short leaves its
        // manifest in the recording before it gets there.
        var traceResult: Result<Trace>? = null
        val outcome = try {
            runArc(arcConfig, case, relay) { manifest -> recordManifest(manifest, handle) }
        } finally {
            traceResult = withContext(NonCancellable) { handle.stop() }
        }
        val trace = checkNotNull(traceResult) { "TraceRecorder handle was not stopped." }

        return when {
            outcome is ArcOutcome.Cancelled ->
                failingResult(
                    case,
                    trace.getOrNull() ?: emptyTrace(runId, case),
                    "Live run was cancelled before it finished.",
                )
            outcome is ArcOutcome.Failed ->
                failingResult(
                    case,
                    trace.getOrNull() ?: emptyTrace(runId, case),
                    "Live run failed: ${outcome.cause.message}",
                )
            trace.isFailure ->
                failingResult(
                    case,
                    emptyTrace(runId, case),
                    "Failed to persist recorded trace: ${trace.exceptionOrNull()?.message}",
                )
            else -> grade(case, trace.getOrThrow())
        }
    }

    /**
     * Runs one case's Arc to a terminal [ArcOutcome].
     *
     * `coroutineScope` makes the run a genuine child of the bench coroutine: agents are bound to
     * it, and cancelling the bench cancels the Arc instead of leaving detached agents behind.
     * Cancellation is not caught here — [AmpereRuntime.execute] already returns
     * [ArcOutcome.Cancelled] for its own cancellation and rethrows the caller's, and swallowing
     * the latter is exactly what the old `runCatching` did wrong. A bench cancelled mid-run still
     * gets its [CompletionManifest] to [completionManifestSink] before that rethrow.
     */
    private suspend fun runArc(
        arcConfig: ArcConfig,
        case: EvalCase,
        relay: CognitiveRelay,
        completionManifestSink: (suspend (CompletionManifest) -> Unit)? = null,
    ): ArcOutcome = coroutineScope {
        AmpereRuntime(
            arcConfig = arcConfig,
            projectDir = projectDir,
            agentScope = this,
            cognitiveRelay = relay,
            executor = NoOpExecutor(),
            maxFlowTicks = maxFlowTicks,
            clock = clock,
            completionManifestSink = completionManifestSink,
        ).execute(case.seed.userGoal)
    }

    /**
     * The live-mode manifest sink (AMPR-359). A case's recorded trace is what outlives the bench,
     * so a cancelled or failed run's manifest goes into it, next to the events of the run it
     * closes out. It is also published through [eventApi] like every other bench event — stored
     * under the Arc run's own id, where that run's `ArcTraceProjection` looks for it.
     *
     * Captured into [recording] directly as well: bus dispatch is asynchronous, and the recording
     * stops the moment the run returns, so a manifest left to the bus alone could miss the trace it
     * belongs to. The bus copy, if it arrives in time, is deduplicated. A failed write is logged by
     * the door; the recording holds the manifest either way.
     */
    private suspend fun recordManifest(manifest: CompletionManifest, recording: RecordingHandle) {
        val event = manifest.toEvent(eventSource = source, timestamp = clock.now())
        recording.capture(event)
        eventApi.publish(event, runId = event.runId)
    }

    private suspend fun grade(case: EvalCase, trace: Trace): EvalCaseResult {
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
        return EvalCaseResult(probeId = case.id, readings = readings, passed = passed, trace = trace)
    }

    private fun failingResult(case: EvalCase, trace: Trace, reason: String): EvalCaseResult =
        EvalCaseResult(
            probeId = case.id,
            readings = listOf(
                Reading(score = 0.0, passed = false, meterId = "bench", detail = mapOf("reason" to reason)),
            ),
            passed = false,
            trace = trace,
        )

    private fun emptyTrace(runId: String, case: EvalCase): Trace =
        Trace(
            id = generateUUID("empty-trace"),
            runId = runId,
            arcId = case.arcId,
            createdAt = 0L,
            events = emptyList(),
        )
}
