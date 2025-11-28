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
        AmpereCommand(context)
            .subcommands(
                WatchCommand(context.eventRelayService),
                ThreadCommand(context.threadViewService),
                StatusCommand(context.threadViewService, context.ticketViewService)
            )
            .main(args)
    } finally {
        // Clean up resources
        context.close()
    }
}

/**
 * Root command for the Ampere CLI.
 *
 * @param context The application context providing access to services
 */
class AmpereCommand(
    private val context: AmpereContext,
) : CliktCommand(
    name = "ampere",
    help = """
        Animated Multi-Agent (Prompting Technique) -> AniMA
        AniMA Model Protocol -> AMP
        AMP Example Runtime Environment -> AMPERE

        AMPERE is a tool for running AniMA simulations in a real-time, observable environment.
    """.trimIndent()
) {
    override fun run() = Unit
}
