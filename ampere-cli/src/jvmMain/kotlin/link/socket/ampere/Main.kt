package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

/**
 * Main entry point for the Ampere CLI.
 *
 * This function:
 * 1. Creates an AmpereContext to initialize all dependencies
 * 2. Starts the environment orchestrator
 * 3. Runs the CLI command
 * 4. Cleans up resources on exit
 */
fun main(args: Array<String>) {
    // Create context with all dependencies
    val context = AmpereContext()

    try {
        // Start all orchestrator services
        context.start()

        // Run the CLI with injected dependencies
        AmpereCommand()
            .subcommands(
                WatchCommand(context.eventRelayService),
                ThreadCommand(context.threadViewService),
                StatusCommand(context.threadViewService, context.ticketViewService),
                OutcomesCommand(context.outcomeMemoryRepository)
            )
            .main(args)
    } finally {
        // Clean up resources
        context.close()
    }
}
