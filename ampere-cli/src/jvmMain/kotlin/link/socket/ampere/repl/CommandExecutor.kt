package link.socket.ampere.repl

import kotlinx.coroutines.*
import org.jline.terminal.Terminal

/**
 * Executes commands in a cancellable coroutine context.
 *
 * This enables graceful interruption - commands can be stopped
 * via Ctrl+C without terminating the parent session.
 */
class CommandExecutor(
    private val terminal: Terminal
) {
    private var currentJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Execute a command in a cancellable context.
     * Returns when the command completes or is interrupted.
     */
    suspend fun execute(block: suspend () -> CommandResult): CommandResult {
        return try {
            coroutineScope {
                currentJob = launch {
                    block()
                }
                currentJob?.join()
                CommandResult.SUCCESS
            }
        } catch (e: CancellationException) {
            terminal.writer().println()
            terminal.writer().println(TerminalColors.warning("Command interrupted"))
            CommandResult.INTERRUPTED
        } catch (e: Exception) {
            terminal.writer().println(TerminalColors.error("Error: ${e.message}"))
            CommandResult.ERROR
        } finally {
            currentJob = null
        }
    }

    /**
     * Cancel the currently executing command.
     * Safe to call even if no command is running.
     */
    fun interrupt() {
        currentJob?.cancel()
        currentJob = null
    }

    fun close() {
        scope.cancel()
    }
}
