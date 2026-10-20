package link.socket.ampere.cli.launch

import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.watch.CommandResult

/**
 * Why the dashboard ended, and the status the process exits with for it (AMPR-467).
 *
 * [QUIT] is a deliberate exit (`q`, `:quit`); [INTERRUPTED] is Ctrl+C, which a shell expects to
 * see as 128 + SIGINT.
 */
enum class DashboardExit(val status: Int) {
    QUIT(0),
    INTERRUPTED(130),
}

/** The exit this key asks for, or null when it asks for none. */
fun DemoInputHandler.KeyResult.requestedExit(): DashboardExit? = when (this) {
    is DemoInputHandler.KeyResult.Exit -> DashboardExit.QUIT
    is DemoInputHandler.KeyResult.Interrupt -> DashboardExit.INTERRUPTED
    else -> null
}

/** The exit this command result asks for, or null when it asks for none. */
fun CommandResult.requestedExit(): DashboardExit? = when (this) {
    is CommandResult.Quit -> DashboardExit.QUIT
    else -> null
}

const val DASHBOARD_TICK_MILLIS: Long = 50

/**
 * Run the dashboard's input and render loops until [exit] completes, then stop both.
 *
 * Every way out — a key, a command, Ctrl+C from a signal handler — completes [exit]; the first one
 * wins. Neither loop ends the dashboard by ending itself: a `CancellationException` thrown inside a
 * child cancels only that child, which is how `q` used to leave the render loop drawing forever.
 *
 * [releaseInput] runs before either loop is waited on, because a key read in flight returns only
 * once the input is closed. The caller's own cleanup runs after this returns, as it would after any
 * other exit; a loop that throws propagates out of here unchanged.
 *
 * @param onKey handles one key, and returns the exit it asks for, if any
 */
suspend fun runDashboardLoops(
    exit: CompletableDeferred<DashboardExit>,
    readKey: suspend () -> Char?,
    onKey: suspend (Char) -> DashboardExit?,
    renderFrame: suspend () -> Unit,
    releaseInput: () -> Unit,
    tick: Duration = DASHBOARD_TICK_MILLIS.milliseconds,
    loopContext: CoroutineContext = Dispatchers.IO,
): DashboardExit = coroutineScope {
    val inputJob = launch(loopContext) {
        while (isActive) {
            readKey()?.let { key -> onKey(key)?.let(exit::complete) }
            delay(tick)
        }
    }
    val renderJob = launch(loopContext) {
        while (isActive) {
            renderFrame()
            delay(tick)
        }
    }
    try {
        exit.await()
    } finally {
        releaseInput()
        inputJob.cancel()
        renderJob.cancel()
    }
}
