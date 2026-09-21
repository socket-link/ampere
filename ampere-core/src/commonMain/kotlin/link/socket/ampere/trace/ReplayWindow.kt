package link.socket.ampere.trace

/**
 * The bounded span of recorded activity that a trace projection folds and a replay covers.
 *
 * ### Why a window is a type of its own
 * Replay walks recorded model calls by **call index** (`PlaybackRelay` hands out
 * `0, 1, 2, …` and treats "index past the recordings" as divergence). An ordered
 * call-index sequence is only meaningful if it has an end, so replay needs a
 * *bounded* window. An unbounded append stream has no end to count towards.
 *
 * In v1 the only bound is the Arc run: [ArcRun] is the whole of this hierarchy, and a
 * run's [ArcRunId] is *how a window is currently identified* — not *what a window is*.
 * A later window shape (for example an explicit turn window under a continuous
 * lifecycle) is added as a new variant, and the exhaustive `when` over this type at
 * each consumer is where the compiler points at the code that has to decide what it
 * means. No `runId`-shaped parameter has to silently change meaning.
 *
 * Recording lives at the bus, not here (AMPR-221 decision #3). The window is a
 * **projection** over what was recorded, never the substrate it is recorded into
 * (AMPR-281 hedge H2, AMPR-285).
 */
sealed interface ReplayWindow {

    /**
     * v1's window: every row recorded under one Arc execution, bounded by the run's start
     * and terminal outcome.
     *
     * @property runId the run whose rows this window covers.
     */
    data class ArcRun(val runId: ArcRunId) : ReplayWindow
}
