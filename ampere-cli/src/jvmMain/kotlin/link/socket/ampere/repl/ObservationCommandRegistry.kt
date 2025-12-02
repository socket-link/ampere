package link.socket.ampere.repl

import kotlinx.coroutines.delay
import link.socket.ampere.*
import link.socket.ampere.renderer.CLIRenderer
import org.jline.terminal.Terminal
import com.github.ajalt.mordant.terminal.Terminal as MordantTerminal

/**
 * Registry of observation commands available in the REPL.
 *
 * These commands provide sensory data about the environment's state
 * (events, threads, tickets, outcomes) and can be interrupted
 * gracefully without terminating the session.
 */
class ObservationCommandRegistry(
    private val context: AmpereContext,
    private val terminal: Terminal,
    private val executor: CommandExecutor
) {
    private val renderer = CLIRenderer(MordantTerminal())

    /**
     * Execute an observation command from user input.
     * Returns null if the command is not an observation command.
     */
    suspend fun executeIfMatches(input: String): CommandResult? {
        val parts = input.split(Regex("\\s+"))
        val command = parts[0].lowercase()

        return when (command) {
            "watch" -> executeWatch(parts.drop(1).toTypedArray())
            "status" -> executeStatus(parts.drop(1).toTypedArray())
            "thread" -> executeThread(parts.drop(1).toTypedArray())
            "outcomes" -> executeOutcomes(parts.drop(1).toTypedArray())
            else -> null  // Not an observation command
        }
    }

    private suspend fun executeWatch(args: Array<String>): CommandResult {
        val indicator = ProgressIndicator(terminal)

        return try {
            indicator.start("Connecting to event stream...")
            delay(300) // Brief pause to show spinner
            indicator.stop()

            terminal.writer().println(TerminalColors.info("Streaming events... Press Ctrl+C to stop"))

            executor.execute {
                val command = WatchCommand(
                    eventRelayService = context.eventRelayService,
                    renderer = renderer
                )
                val adapter = CommandAdapter(terminal)
                adapter.execute(command, args)
            }
        } finally {
            indicator.stop()
        }
    }

    private suspend fun executeStatus(args: Array<String>): CommandResult {
        return executor.execute {
            val command = StatusCommand(
                threadViewService = context.threadViewService,
                ticketViewService = context.ticketViewService
            )
            val adapter = CommandAdapter(terminal)
            adapter.execute(command, args)
        }
    }

    private suspend fun executeThread(args: Array<String>): CommandResult {
        return executor.execute {
            val command = ThreadCommand(
                threadViewService = context.threadViewService,
                renderer = renderer
            )
            val adapter = CommandAdapter(terminal)
            adapter.execute(command, args)
        }
    }

    private suspend fun executeOutcomes(args: Array<String>): CommandResult {
        return executor.execute {
            val command = OutcomesCommand(
                outcomeRepository = context.outcomeMemoryRepository
            )
            val adapter = CommandAdapter(terminal)
            adapter.execute(command, args)
        }
    }
}
