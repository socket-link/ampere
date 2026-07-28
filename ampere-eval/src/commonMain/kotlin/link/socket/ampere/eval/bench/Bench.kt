package link.socket.ampere.eval.bench

import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.BenchEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.NoOpExecutor
import link.socket.ampere.domain.arc.AmpereRuntime
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ArcRegistry
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.relay.MissPolicy
import link.socket.ampere.eval.relay.PlaybackRelay
import link.socket.ampere.eval.trace.Trace
import link.socket.ampere.eval.trace.TraceRecorder
import okio.Path

/**
 * Runs a suite of [Probe]s against an Arc, in either [RunMode.Replay] (a golden [Trace] +
 * `PlaybackRelay`, deterministic and CI-safe) or [RunMode.Live] (the real relay, recording a
 * fresh trace via [TraceRecorder]; nightly/on-demand only).
 *
 * Arcs run with tools in effect-free mode ([NoOpExecutor]) for every probe, regardless of
 * [RunMode] — a bench run never performs a real tool side effect.
 *
 * @param liveModeEnabled explicit opt-in flag for [RunMode.Live] (AMPR-186 task 4.4); defaults
 *   `false` so [RunMode.Live] is refused unless a caller deliberately enables it. CI wiring
 *   never sets this, which is what keeps Live mode out of CI (ticket 5, out of scope here).
 */
class Bench(
    private val projectDir: Path,
    private val eventBus: EventSerialBus,
    private val liveRelay: CognitiveRelay? = null,
    private val traceRecorder: TraceRecorder? = null,
    private val liveModeEnabled: Boolean = false,
    private val source: EventSource = EventSource.Human,
    private val maxFlowTicks: Int = 100,
) {

    suspend fun run(suite: List<Probe>, mode: RunMode): Result<BenchReport> {
        if (mode is RunMode.Live && !liveModeEnabled) {
            return Result.failure(
                IllegalStateException("RunMode.Live requires Bench to be constructed with liveModeEnabled = true."),
            )
        }

        val runId = generateUUID("bench-run")
        eventBus.publish(
            BenchEvent.BenchRunStarted(
                eventId = generateUUID("bench-started", runId),
                runId = runId,
                eventSource = source,
                timestamp = Clock.System.now(),
                mode = mode.toString(),
                probeCount = suite.size,
            ),
        )

        val results = suite.map { probe ->
            val result = runProbe(runId, probe, mode)
            eventBus.publish(
                BenchEvent.ProbeGraded(
                    eventId = generateUUID("probe-graded", runId, probe.id),
                    runId = runId,
                    eventSource = source,
                    timestamp = Clock.System.now(),
                    probeId = probe.id,
                    passed = result.passed,
                    meanScore = result.readings.map { it.score }.average().takeUnless { it.isNaN() } ?: 0.0,
                ),
            )
            result
        }

        val passRate = if (results.isEmpty()) 0.0 else results.count { it.passed }.toDouble() / results.size
        val report = BenchReport(results = results, passRate = passRate)

        eventBus.publish(
            BenchEvent.BenchRunCompleted(
                eventId = generateUUID("bench-completed", runId),
                runId = runId,
                eventSource = source,
                timestamp = Clock.System.now(),
                passRate = report.passRate,
                probeCount = results.size,
            ),
        )

        return Result.success(report)
    }

    /**
     * Runs and grades a single probe. A probe-level failure (unresolved arc, missing golden
     * trace, `PlaybackMiss` divergence, or a runtime exception) degrades to a failing
     * [ProbeResult] rather than aborting the whole suite, so one probe's divergence doesn't
     * blank out the report (AMPR-186 task 4.5: "report aggregates all probes").
     */
    private suspend fun runProbe(runId: String, probe: Probe, mode: RunMode): ProbeResult {
        val arcConfig = ArcRegistry.get(probe.arcId)
            ?: return failingResult(
                probe,
                emptyTrace(runId, probe),
                "No ArcConfig registered for arcId '${probe.arcId}'.",
            )

        return when (mode) {
            RunMode.Replay -> runReplay(runId, probe, arcConfig)
            RunMode.Live -> runLive(runId, probe, arcConfig)
        }
    }

    private suspend fun runReplay(runId: String, probe: Probe, arcConfig: ArcConfig): ProbeResult {
        val goldenTrace = probe.goldenTrace
            ?: return failingResult(
                probe,
                emptyTrace(runId, probe),
                "Probe '${probe.id}' has no goldenTrace for Replay mode.",
            )

        val playbackRelay = PlaybackRelay(trace = goldenTrace, missPolicy = MissPolicy.Error)

        val runResult = runCatching {
            AmpereRuntime(
                arcConfig = arcConfig,
                projectDir = projectDir,
                cognitiveRelay = playbackRelay,
                executor = NoOpExecutor(),
                maxFlowTicks = maxFlowTicks,
            ).execute(probe.seed.userGoal)
        }

        return runResult.fold(
            onSuccess = { grade(probe, goldenTrace) },
            onFailure = { error ->
                failingResult(probe, goldenTrace, "Arc run diverged from goldenTrace: ${error.message}")
            },
        )
    }

    private suspend fun runLive(runId: String, probe: Probe, arcConfig: ArcConfig): ProbeResult {
        val relay = liveRelay
            ?: return failingResult(
                probe,
                emptyTrace(runId, probe),
                "RunMode.Live requires a liveRelay to be configured on Bench.",
            )
        val recorder = traceRecorder
            ?: return failingResult(
                probe,
                emptyTrace(runId, probe),
                "RunMode.Live requires a traceRecorder to be configured on Bench.",
            )

        val handle = recorder.start(runId = runId, arcId = probe.arcId)

        val runResult = runCatching {
            AmpereRuntime(
                arcConfig = arcConfig,
                projectDir = projectDir,
                cognitiveRelay = relay,
                executor = NoOpExecutor(),
                maxFlowTicks = maxFlowTicks,
            ).execute(probe.seed.userGoal)
        }

        val traceResult = handle.stop()

        return when {
            runResult.isFailure ->
                failingResult(
                    probe,
                    traceResult.getOrNull() ?: emptyTrace(runId, probe),
                    "Live run failed: ${runResult.exceptionOrNull()?.message}",
                )
            traceResult.isFailure ->
                failingResult(
                    probe,
                    emptyTrace(runId, probe),
                    "Failed to persist recorded trace: ${traceResult.exceptionOrNull()?.message}",
                )
            else -> grade(probe, traceResult.getOrThrow())
        }
    }

    private suspend fun grade(probe: Probe, trace: Trace): ProbeResult {
        val readings = probe.meters.map { meter ->
            meter.measure(trace).getOrElse { error ->
                Reading(
                    score = 0.0,
                    passed = false,
                    meterId = "error",
                    detail = mapOf("error" to (error.message ?: "unknown")),
                )
            }
        }
        val passed = readings.isNotEmpty() && readings.all { probe.tolerance.passes(it.score) }
        return ProbeResult(probeId = probe.id, readings = readings, passed = passed, trace = trace)
    }

    private fun failingResult(probe: Probe, trace: Trace, reason: String): ProbeResult =
        ProbeResult(
            probeId = probe.id,
            readings = listOf(
                Reading(score = 0.0, passed = false, meterId = "bench", detail = mapOf("reason" to reason)),
            ),
            passed = false,
            trace = trace,
        )

    private fun emptyTrace(runId: String, probe: Probe): Trace =
        Trace(
            id = generateUUID("empty-trace"),
            runId = runId,
            arcId = probe.arcId,
            createdAt = 0L,
            events = emptyList(),
        )
}
