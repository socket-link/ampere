package link.socket.ampere.eval.bench

import kotlinx.serialization.Serializable
import link.socket.ampere.eval.meter.Meter
import link.socket.ampere.eval.meter.Reading
import link.socket.ampere.eval.meter.Tolerance
import link.socket.ampere.eval.trace.Trace

/** The seed that triggers an Arc run: the user goal handed to `AmpereRuntime.execute`. */
data class ProbeSeed(val userGoal: String)

/**
 * One eval case: a seed that triggers an Arc, plus the meters and tolerance that grade it.
 *
 * @property arcId looked up via `ArcRegistry.get` to resolve the `ArcConfig` to run.
 * @property goldenTrace required for [RunMode.Replay] (injected into a `PlaybackRelay`);
 *   absent for probes that only ever run in [RunMode.Live].
 */
data class Probe(
    val id: String,
    val arcId: String,
    val seed: ProbeSeed,
    val meters: List<Meter>,
    val tolerance: Tolerance,
    val goldenTrace: Trace? = null,
)

/** Whether a [Bench] run replays a golden [Trace] (CI-safe) or drives the real relay (nightly/on-demand). */
@Serializable
sealed interface RunMode {
    @Serializable
    data object Replay : RunMode

    @Serializable
    data object Live : RunMode
}

data class ProbeResult(
    val probeId: String,
    val readings: List<Reading>,
    val passed: Boolean,
    val trace: Trace,
)

data class BenchReport(
    val results: List<ProbeResult>,
    val passRate: Double,
)
