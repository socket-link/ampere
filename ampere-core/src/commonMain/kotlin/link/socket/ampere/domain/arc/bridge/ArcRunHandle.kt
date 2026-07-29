package link.socket.ampere.domain.arc.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import link.socket.ampere.agents.domain.emission.Emission
import link.socket.ampere.domain.arc.AmpereRuntime
import link.socket.ampere.domain.arc.ArcOutcome
import link.socket.ampere.trace.ArcRunId
import link.socket.ampere.trace.ArcRunTrace
import link.socket.ampere.trace.ArcTraceProjection

/**
 * A running Arc, as seen from outside the coroutine world.
 *
 * Created by [ArcSession.start]. The handle observes; it does not orchestrate — the run's
 * lifetime belongs to the [AmpereRuntime] and the scope the session was built with.
 *
 * Every method is safe to call from any thread, in any order, more than once.
 *
 * ### Swift
 *
 * The Kotlin/Native Objective-C export turns each `suspend` function into a completion-handler
 * method, which Swift re-imports as `async`. The call site reads the way a SKIE-generated one
 * would, which is the point — adopting SKIE later should be a build-file change, not a Swift
 * rewrite.
 *
 * ```swift
 * let handle = session.start(userGoal: goal)
 * Task { for await e in handle.emissions { await reporter.update(e) } }  // ProgressReportingIntent
 * let outcome = try await handle.await()                                 // LongRunningIntent
 * let cancelled = try await handle.cancel()                              // stop button
 * ```
 */
class ArcRunHandle internal constructor(
    /** Ambient identity of this run. Every event, trace row, and Emission it produces carries it. */
    val runId: ArcRunId,
    private val runtime: AmpereRuntime,
    private val scope: CoroutineScope,
    private val outcome: Deferred<ArcOutcome>,
    private val emissions: Flow<Emission>,
    private val traceProjection: ArcTraceProjection?,
) {
    /**
     * Suspend until the run reaches a terminal [ArcOutcome].
     *
     * Returns rather than throws for Arc-level failure and cancellation — those are
     * [ArcOutcome.Failed] and [ArcOutcome.Cancelled]. Cancelling the *caller* still throws, as
     * structured concurrency requires; in Swift that surfaces as the enclosing `Task` being
     * cancelled.
     *
     * Idempotent: every call after the first returns the same outcome immediately.
     */
    suspend fun await(): ArcOutcome = outcome.await()

    /**
     * Deliver this run's Emissions to [onEmission] until the run reaches a terminal outcome or
     * the returned [ArcCancellable] is cancelled.
     *
     * Emissions produced before the first observer attaches are replayed from the session's
     * buffer, so a Swift `Task { for await ... }` that starts a beat after [ArcSession.start]
     * does not miss the opening of the run. A consumer that falls further behind than the
     * session's buffer loses the oldest Emissions, reported through the session's
     * `onEmissionsDropped` callback rather than dropped in silence.
     */
    fun observe(onEmission: (Emission) -> Unit): ArcCancellable =
        observe(onEmission = onEmission, onFinished = {})

    /**
     * [observe], plus a terminal callback.
     *
     * [onFinished] runs exactly once — after the run ends, or after the returned
     * [ArcCancellable] is cancelled. It is what lets a Swift `AsyncStream` call `finish()`
     * instead of hanging a `for await` loop forever on a run that is already over.
     *
     * Emissions still in flight on the bus when the run terminates may not arrive: bus dispatch
     * is asynchronous and the terminal outcome does not wait on it. The outcome is the
     * authoritative end of the run; the Emission stream is progress, not a ledger.
     */
    fun observe(onEmission: (Emission) -> Unit, onFinished: () -> Unit): ArcCancellable {
        val job = scope.launch {
            try {
                coroutineScope {
                    val collector = launch { emissions.collect { onEmission(it) } }
                    outcome.join()
                    collector.cancel()
                }
            } finally {
                onFinished()
            }
        }

        return ArcCancellable { job.cancel() }
    }

    /**
     * Halt the run cooperatively and suspend until it has settled.
     *
     * The ordering is what makes the ledger correct, and it is why this suspends rather than
     * returning immediately:
     *
     * 1. [AmpereRuntime.cancel] cancels the run's job; `FlowPhase`'s tick loop observes it at
     *    its next `ensureActive()` and terminates with `TerminationReason.CANCELLED`.
     * 2. An in-flight LLM call unwinds through its `CancellationException` handler, which
     *    settles a cost record under `NonCancellable` before rethrowing.
     * 3. `execute` joins every agent coroutine before it returns, so those settlement writes are
     *    durable by the time this function does.
     *
     * Returns the run's terminal outcome — [ArcOutcome.Cancelled] in the ordinary case. A run
     * that had already completed or failed before the cancel landed returns *that* outcome
     * instead: this reports what happened, it does not relabel it.
     *
     * Idempotent, and safe to call before the run has been dispatched.
     */
    suspend fun cancel(): ArcOutcome {
        // The runtime only holds a cancellable job once `execute` is under way. Cancelling
        // before that has nothing to act on, so wait for the run to be marked running —
        // bounded by the run finishing on its own, which makes this terminate either way.
        while (outcome.isActive && !runtime.isRunning()) {
            yield()
        }

        runtime.cancel()

        return outcome.await()
    }

    /**
     * Fold this run's persisted rows into a trace: phases, model invocations, memory writes,
     * tool calls, and the Watt cost actually incurred.
     *
     * Call it after [await] or [cancel]. Both guarantee that every agent coroutine — including
     * the `NonCancellable` blocks that settle cost records for cancelled LLM calls — has
     * completed, so the fold sees actuals rather than a half-written ledger.
     *
     * Null when the session was built without an [ArcTraceProjection], or when the run wrote no
     * rows at all (a runtime configured without an event API persists no telemetry).
     */
    suspend fun trace(): ArcRunTrace? = traceProjection?.project(runId)?.getOrNull()

    /** True while the run is still in flight. */
    val isActive: Boolean
        get() = outcome.isActive
}
