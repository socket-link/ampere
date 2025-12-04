package link.socket.ampere

import com.github.ajalt.clikt.core.subcommands

/**
 * Main entry point for the Ampere CLI.
 *
 * This function:
 * 1. Creates an AmpereContext to initialize all dependencies
 * 2. Starts the environment orchestrator
 * 3. Runs the CLI command (defaults to interactive mode if no args)
 * 4. Cleans up resources on exit
 */
fun main(args: Array<String>) {
    // Create context with all dependencies
    val context = AmpereContext()

    try {
        // Start all orchestrator services
        context.start()

        // If no arguments provided, launch interactive mode
        val effectiveArgs = if (args.isEmpty()) {
            arrayOf("interactive")
        } else {
            args
        }

        // Run the CLI with injected dependencies
        AmpereCommand()
            .subcommands(
                InteractiveCommand(context),
                WatchCommand(context.eventRelayService),
                ThreadCommand(context.threadViewService),
                StatusCommand(context.threadViewService, context.ticketViewService),
                OutcomesCommand(context.outcomeMemoryRepository)
            )
            .main(effectiveArgs)
    } finally {
        // Clean up resources
        context.close()
    }
}
