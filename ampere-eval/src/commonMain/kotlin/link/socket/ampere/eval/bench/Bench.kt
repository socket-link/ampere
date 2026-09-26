package link.socket.ampere.eval.bench

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.BenchEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.outcome.Outcome
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
import link.socket.ampere.eval.relay.PlaybackMiss
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
 * ### What the meters see
 *
 * Every case's run is recorded when [traceRecorder] is set, in **both** modes, and it is that
 * freshly recorded [Trace] the case's `Meter`s grade. That is what makes a Replay suite a
 * regression gate rather than a restatement of its own recording (AMPR-187): the golden trace
 * drives the `PlaybackRelay` and is the reference a `TraceConformanceMeter` compares against,
 * while the readings describe what the Arc did *now*.
 *
 * With no [traceRecorder], a [RunMode.Replay] case falls back to grading its golden trace, which
 * detects a divergence or a failure but cannot detect a behavior change that still completes.
 * [RunMode.Live] has no such fallback — recording is the point of it.
 *
 * @param eventApi the door every [BenchEvent] is published through: persisted to the
 *   `EventStore` under the bench run's id, then dispatched on the bus (F1). A [TraceRecorder]
 *   should subscribe on `eventApi.eventSerialBus`, so the store and the trace see the same
 *   stream. A failed publish fails the run rather than being dropped.
 * @param liveModeEnabled explicit opt-in flag for [RunMode.Live] (AMPR-186 task 4.4); defaults
 *   `false` so [RunMode.Live] is refused unless a caller deliberately enables it. CI wiring
 *   never sets this, which is what keeps Live mode out of CI.
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

        val recorder = traceRecorder
            ?: return runUnrecorded(runId, case, arcConfig, playbackRelay, goldenTrace)

        return runRecorded(runId, case, arcConfig, playbackRelay, recorder, RunMode.Replay)
    }

    /**
     * The Replay path for a [Bench] built without a [traceRecorder]: run the case, then grade its
     * **golden** trace, because there is no fresh one.
     *
     * This is the pre-AMPR-187 behavior, kept for callers that only want the divergence signal —
     * a `PlaybackMiss` or a failed run still fails the case. What it cannot see is a behavior
     * change that still completes, since the trace it grades is the one the meters were written
     * against. Give the bench a recorder to close that gap.
     */
    private suspend fun runUnrecorded(
        runId: String,
        case: EvalCase,
        arcConfig: ArcConfig,
        relay: CognitiveRelay,
        goldenTrace: Trace,
    ): EvalCaseResult {
        val outcome = runArc(runId, case, arcConfig, relay)
        val published = publishArcSettled(runId, case, outcome, recording = null)
        return if (published.isFailure) {
            failingResult(case, goldenTrace, "Failed to publish ArcSettled: ${published.exceptionOrNull()?.message}")
        } else {
            grade(case, outcome, goldenTrace, RunMode.Replay)
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

        return runRecorded(runId, case, arcConfig, relay, recorder, RunMode.Live)
    }

    /**
     * Runs one case with its bus stream recorded, and grades the recording.
     *
     * The recording handle must be closed even if the bench coroutine is cancelled, so it is
     * stopped in a `finally` rather than only on the happy path — a run that is cut short leaves
     * its manifest in the recording before it gets there.
     */
    private suspend fun runRecorded(
        runId: String,
        case: EvalCase,
        arcConfig: ArcConfig,
        relay: CognitiveRelay,
        recorder: TraceRecorder,
        mode: RunMode,
    ): EvalCaseResult {
        val handle = recorder.start(runId = runId, arcId = case.arcId)

        var traceResult: Result<Trace>? = null
        var settledPublish: Result<Unit> = Result.success(Unit)
        val outcome = try {
            val settled = runArc(runId, case, arcConfig, relay) { manifest -> recordManifest(manifest, handle) }
            settledPublish = publishArcSettled(runId, case, settled, handle)
            settled
        } finally {
            traceResult = withContext(NonCancellable) { handle.stop() }
        }
        val trace = checkNotNull(traceResult) { "TraceRecorder handle was not stopped." }

        return when {
            trace.isFailure -> failingResult(
                case,
                emptyTrace(runId, case),
                "Failed to persist recorded trace: ${trace.exceptionOrNull()?.message}",
            )

            // Reported against the recorded trace, which now exists — the run happened, and only
            // the record of how it settled is missing. That is a finding, not something to drop.
            settledPublish.isFailure -> failingResult(
                case,
                trace.getOrThrow(),
                "Failed to publish ArcSettled: ${settledPublish.exceptionOrNull()?.message}",
            )

            else -> grade(case, outcome, trace.getOrThrow(), mode)
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
        runId: String,
        case: EvalCase,
        arcConfig: ArcConfig,
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
        ).execute(case.seed.userGoal, runId = arcRunId(runId, case))
    }

    /**
     * The Arc run id for [case] within bench run [runId].
     *
     * Derived rather than random so a recorded trace's `ArcSettled.arcRunId` names the case it
     * belongs to, which is what makes a golden trace readable. It is still unique per bench run,
     * because [runId] is.
     */
    private fun arcRunId(runId: String, case: EvalCase): String = "$runId/${case.id}"

    /**
     * Publishes how [outcome] settled, and captures it into [recording] directly when there is one.
     *
     * Published for every case in either mode, recorded or not: it is the bench's statement about
     * the run, not an artifact of recording one, and a caller watching the bus should not need a
     * recorder to hear it.
     *
     * Captured as well as published, for the reason [recordManifest] gives: bus dispatch is
     * asynchronous and the recording closes the moment this returns, so an event left to the bus
     * alone could miss the trace it belongs to. The bus copy, if it arrives in time, is
     * deduplicated by event id.
     *
     * This is the event a golden trace of a *successful* run is built on — `AmpereRuntime`
     * publishes nothing for a run that closed its loop. See `BenchEvent.ArcSettled`.
     */
    private suspend fun publishArcSettled(
        runId: String,
        case: EvalCase,
        outcome: ArcOutcome,
        recording: RecordingHandle?,
    ): Result<Unit> {
        val outcomes = outcome.flowResult?.agentOutcomes?.values?.flatten() ?: emptyList()
        val event = BenchEvent.ArcSettled(
            eventId = generateUUID("arc-settled", runId, case.id),
            runId = runId,
            eventSource = source,
            timestamp = clock.now(),
            probeId = case.id,
            arcId = case.arcId,
            arcRunId = outcome.runId,
            terminal = when (outcome) {
                is ArcOutcome.Completed -> BenchEvent.ArcTerminal.COMPLETED
                is ArcOutcome.Failed -> BenchEvent.ArcTerminal.FAILED
                is ArcOutcome.Cancelled -> BenchEvent.ArcTerminal.CANCELLED
            },
            terminationReason = outcome.flowResult?.terminationReason,
            pulseSuccess = (outcome as? ArcOutcome.Completed)?.pulseResult?.success,
            agentCount = outcome.chargeResult?.agents?.size ?: 0,
            goalNodeCount = outcome.chargeResult?.goalTree?.allNodes()?.size ?: 0,
            completedGoalCount = outcome.flowResult?.completedGoals?.size ?: 0,
            finalTick = outcome.flowResult?.finalTick,
            outcomeTotal = outcomes.size,
            outcomeSucceeded = outcomes.count { it is Outcome.Success },
            outcomeFailed = outcomes.count { it is Outcome.Failure },
            failure = (outcome as? ArcOutcome.Failed)?.cause?.describe(),
        )
        recording?.capture(event)
        return eventApi.publish(event, runId = runId).map { }
    }

    /** `Type: message`, clipped, for [BenchEvent.ArcSettled.failure]. */
    private fun Throwable.describe(): String {
        val line = "${this::class.simpleName ?: "Throwable"}: ${message ?: "(no message)"}"
        val max = BenchEvent.ArcSettled.MAX_FAILURE_CHARS
        return if (line.length <= max) line else line.take(max - 1) + "…"
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

    /**
     * Grades a settled run: a completed one goes to the case's meters, and a cancelled or failed
     * one degrades to a single failing reading naming what happened.
     */
    private suspend fun grade(case: EvalCase, outcome: ArcOutcome, trace: Trace, mode: RunMode): EvalCaseResult =
        when (outcome) {
            is ArcOutcome.Completed -> measure(case, trace)
            is ArcOutcome.Cancelled -> failingResult(case, trace, "${mode.label} run was cancelled before it finished.")
            is ArcOutcome.Failed -> failingResult(case, trace, outcome.reason(mode))
        }

    /**
     * Why a failed run failed, from the case's point of view. A [PlaybackMiss] is not an ordinary
     * failure: it means the Arc asked for a model call the golden trace has no recording of, which
     * is divergence from the recording rather than a broken run.
     */
    private fun ArcOutcome.Failed.reason(mode: RunMode): String = when (cause) {
        is PlaybackMiss -> "Arc run diverged from goldenTrace: ${cause.message}"
        else -> "${mode.label} run failed: ${cause.message}"
    }

    private suspend fun measure(case: EvalCase, trace: Trace): EvalCaseResult {
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
