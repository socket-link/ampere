package link.socket.ampere.eval.bench

import kotlinx.serialization.Serializable
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance
import link.socket.ampere.eval.trace.Trace

/** The seed that triggers an Arc run: the user goal handed to `AmpereRuntime.execute`. */
data class EvalSeed(val userGoal: String)

@Deprecated("Renamed EvalSeed", ReplaceWith("EvalSeed"))
typealias ProbeSeed = EvalSeed

/**
 * One eval case: a seed that triggers an Arc, plus the meters and tolerance that grade it.
 *
 * Formerly named `Probe` — renamed (AMPR-318) so that name is free for the
 * `link.socket.ampere.probe.Probe` SPI in ampere-core, a predicate over a
 * static artifact rather than a grader of a trace, which is what this type is.
 *
 * @property arcId looked up via `ArcRegistry.get` to resolve the `ArcConfig` to run.
 * @property goldenTrace required for [RunMode.Replay] (injected into a `PlaybackRelay`);
 *   absent for cases that only ever run in [RunMode.Live].
 */
data class EvalCase(
    val id: String,
    val arcId: String,
    val seed: EvalSeed,
    val meters: List<Meter>,
    val tolerance: Tolerance,
    val goldenTrace: Trace? = null,
)

@Deprecated("Renamed EvalCase", ReplaceWith("EvalCase"))
typealias Probe = EvalCase

/** Whether a [Bench] run replays a golden [Trace] (CI-safe) or drives the real relay (nightly/on-demand). */
@Serializable
sealed interface RunMode {

    /** How this mode names itself in a reading's failure detail — "Replay run failed: …". */
    val label: String

    @Serializable
    data object Replay : RunMode {
        override val label: String get() = "Replay"
    }

    @Serializable
    data object Live : RunMode {
        override val label: String get() = "Live"
    }
}

data class EvalCaseResult(
    val probeId: String,
    val readings: List<Reading>,
    val passed: Boolean,
    val trace: Trace,
)

@Deprecated("Renamed EvalCaseResult", ReplaceWith("EvalCaseResult"))
typealias ProbeResult = EvalCaseResult

data class BenchReport(
    val results: List<EvalCaseResult>,
    val passRate: Double,
)
