package link.socket.ampere.cli.launch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.watch.CommandExecutor
import link.socket.ampere.cli.watch.CommandResult

/**
 * The dashboard's command line, run off its input loop (AMPR-468).
 *
 * A command runs on [scope], a scope the dashboard owns, so the input loop keeps reading keys while
 * it waits: `:ask` can take a minute on a loaded machine. Until it returns, [screen] is its
 * [CommandResult.Pending]; then it is the command's result, until a key dismisses it.
 *
 * One command at a time: a command typed while another runs is refused with a notice on the waiting
 * screen, never queued. The quit commands are the exception, answered here without running, so
 * `:quit` ends the dashboard whatever is pending.
 *
 * [cancel] cancels the running command as cancellation, never as a failed command: for `:ask` that
 * is what ends the helper's request.
 *
 * @param execute runs one command: `CommandExecutor.execute` in production
 * @param describePending what to draw while a command runs: `CommandExecutor.describePending`
 */
class DashboardCommands(
    private val scope: CoroutineScope,
    private val execute: suspend (String) -> CommandResult,
    private val describePending: (String) -> CommandResult.Pending,
) {
    private sealed interface State {
        data object Idle : State
        class Running(val job: Job, val pending: CommandResult.Pending) : State
        class Shown(val result: CommandResult) : State
    }

    private val lock = Any()
    private var state: State = State.Idle

    /** What the render loop draws in place of the dashboard, or null to draw the dashboard. */
    val screen: CommandResult?
        get() = synchronized(lock) {
            when (val current = state) {
                State.Idle -> null
                is State.Running -> current.pending
                is State.Shown -> current.result
            }
        }

    /** Whether a command is still running. */
    val isPending: Boolean
        get() = synchronized(lock) { state is State.Running }

    /**
     * Run [command], entered on the command line. Returns the exit it asks for, if any; a command
     * that runs returns null at once, and its result reaches [screen] when it returns.
     */
    fun submit(command: String): DashboardExit? {
        if (CommandExecutor.isQuit(command)) return DashboardExit.QUIT

        synchronized(lock) {
            val current = state
            if (current is State.Running) {
                state = State.Running(
                    job = current.job,
                    pending = current.pending.copy(
                        notice = ":${command.trim()} was not run: wait for this one, or press ESC to cancel it.",
                    ),
                )
                return null
            }

            // Started only once it is the state, so a command that returns at once cannot publish
            // its result ahead of its own pending screen.
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    execute(command)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    CommandResult.Error("Command failed: ${failure.message}")
                }
                // A command that swallowed its own cancellation has nothing to show.
                ensureActive()
                publish(job, result)
            }
            state = State.Running(job, describePending(command))
            job.start()
        }
        return null
    }

    /** Cancel the running command and return to the dashboard. False when none is running. */
    fun cancel(): Boolean {
        val job = synchronized(lock) {
            val current = state as? State.Running ?: return false
            state = State.Idle
            current.job
        }
        job.cancel(CancellationException("Cancelled from the dashboard"))
        return true
    }

    /** Dismiss a result being shown and return to the dashboard. False when none is shown. */
    fun dismiss(): Boolean = synchronized(lock) {
        if (state !is State.Shown) return false
        state = State.Idle
        true
    }

    /** Wait for the running command, if any, to return or finish cancelling. */
    suspend fun awaitIdle() {
        val job = synchronized(lock) { (state as? State.Running)?.job }
        job?.join()
    }

    private fun publish(job: Job, result: CommandResult) = synchronized(lock) {
        // Only the command the dashboard is still waiting on shows its result.
        val current = state
        if (current is State.Running && current.job === job) state = State.Shown(result)
    }
}

/**
 * Give [key] to the command line before the view sees it. Returns true when it was spent here.
 *
 * Any key dismisses a result being shown. ESC cancels a running command, unless it is closing a
 * command line being typed over the waiting screen. Every other key, `q` and Ctrl+C among them,
 * goes on to the view.
 */
fun DashboardCommands.intercept(key: Char, config: DemoInputHandler.DemoViewConfig): Boolean {
    if (dismiss()) return true
    val typing = config.inputMode != DemoInputHandler.InputMode.NORMAL
    return key == ESC && !typing && cancel()
}

private const val ESC: Char = '\u001B'
