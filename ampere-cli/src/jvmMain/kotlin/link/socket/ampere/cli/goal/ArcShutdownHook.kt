package link.socket.ampere.cli.goal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the CLI holds shutdown for an Arc run to settle: to unwind, and to write the completion
 * manifest of a run cut short (AMPR-359). A bound, not an expected wait — a settled run takes
 * milliseconds.
 */
internal val ARC_SETTLE_GRACE: Duration = 5.seconds

/**
 * Turns a terminal interrupt into an Arc cancellation the run can record (AMPR-359).
 *
 * SIGINT and SIGTERM end the JVM without unwinding a single coroutine, so an Arc run interrupted
 * from the terminal would otherwise leave no completion manifest behind. While [install]ed, a
 * shutdown calls [cancelRun] and then holds the JVM open — for at most [grace] — until [release]
 * says the run has settled, by which point its manifest is written.
 *
 * ```kotlin
 * val hook = ArcShutdownHook(cancelRun = runtime::cancel).install()
 * try {
 *     report(runtime.execute(goal))
 * } finally {
 *     hook.release()
 * }
 * ```
 */
internal class ArcShutdownHook(
    private val cancelRun: () -> Unit,
    private val grace: Duration = ARC_SETTLE_GRACE,
) {
    private val settled = CompletableDeferred<Unit>()
    private val thread = Thread(::onShutdown, "ampere-arc-shutdown")

    /** Register with the JVM. Returns this hook, for [release] once the run is over. */
    fun install(): ArcShutdownHook = apply { Runtime.getRuntime().addShutdownHook(thread) }

    /** The run has settled and been reported: stop holding shutdown for it. Idempotent. */
    fun release() {
        settled.complete(Unit)
        try {
            Runtime.getRuntime().removeShutdownHook(thread)
        } catch (_: IllegalStateException) {
            // The JVM is already shutting down, and this hook is what is waiting on `settled`.
        }
    }

    /** What the hook thread runs: cancel the run, then wait — bounded — for it to settle. */
    internal fun onShutdown() {
        cancelRun()
        runBlocking { withTimeoutOrNull(grace) { settled.await() } }
    }
}
