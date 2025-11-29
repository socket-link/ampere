package link.socket.ampere.repl

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.AmpereContext
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import sun.misc.Signal

/**
 * Manages an interactive REPL session for the AMPERE CLI.
 *
 * The REPL session maintains a persistent environment where multiple
 * commands can be executed sequentially without restarting the substrate.
 * This is the "brainstem interface" connecting human input to agent activity.
 */
class ReplSession(
    private val context: AmpereContext
) {
    private val terminal: Terminal = TerminalBuilder.builder()
        .system(true)
        .build()

    private val reader: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .build()

    private val executor = CommandExecutor(terminal)

    // Add registry for observation commands
    private val observationCommands = ObservationCommandRegistry(
        context,
        terminal,
        executor
    )

    init {
        // Install signal handler for Ctrl+C
        installSignalHandler()
    }

    /**
     * Install SIGINT (Ctrl+C) handler that interrupts current command
     * but doesn't exit the REPL session.
     */
    private fun installSignalHandler() {
        Signal.handle(Signal("INT")) { signal ->
            // Interrupt any running command
            executor.interrupt()
        }
    }

    /**
     * Start the REPL loop.
     * Displays welcome banner, then enters command loop until exit.
     */
    fun start() {
        displayWelcomeBanner()
        runCommandLoop()
    }

    private fun displayWelcomeBanner() {
        val banner = """
        ╔═══════════════════════════════════════════════════════╗
        ║  AMPERE Interactive Shell v0.1.0                      ║
        ║  Autonomous Multi-Process Execution & Relay Env       ║
        ║                                                       ║
        ║  Type 'help' for commands, 'exit' to quit            ║
        ║  Press Ctrl+C to interrupt running observations       ║
        ╚═══════════════════════════════════════════════════════╝
        """.trimIndent()

        terminal.writer().println(banner)
        terminal.writer().println()

        // Display initial system status
        displaySystemStatus()
        terminal.writer().println()
    }

    private fun displaySystemStatus() {
        // TODO: Pull actual metrics from context services
        terminal.writer().println("System Status: ● Running")
    }

    private fun runCommandLoop() {
        while (true) {
            try {
                val line = reader.readLine("ampere> ")

                if (line.isNullOrBlank()) {
                    continue
                }

                // Execute command in cancellable context
                val result = runBlocking {
                    executeCommand(line.trim())
                }

                if (result == CommandResult.EXIT) {
                    terminal.writer().println("Goodbye! Shutting down environment...")
                    break
                }

            } catch (e: Exception) {
                terminal.writer().println("Error: ${e.message}")
            }
        }
    }

    private suspend fun executeCommand(input: String): CommandResult {
        // First check if it's an observation command
        val observationResult = observationCommands.executeIfMatches(input)
        if (observationResult != null) {
            return observationResult
        }

        // Otherwise check built-in REPL commands
        val parts = input.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val args = parts.getOrNull(1) ?: ""

        return when (command) {
            "exit", "quit" -> CommandResult.EXIT
            "help" -> {
                displayHelp()
                CommandResult.SUCCESS
            }
            "test-interrupt" -> {
                // Test command for verifying interruption works
                executor.execute {
                    terminal.writer().println("Running for 30 seconds... Press Ctrl+C to interrupt")
                    delay(30000)
                    terminal.writer().println("Completed!")
                    CommandResult.SUCCESS
                }
            }
            else -> {
                terminal.writer().println("Unknown command: $command")
                terminal.writer().println("Type 'help' for available commands")
                CommandResult.ERROR
            }
        }
    }

    private fun displayHelp() {
        val help = """
        Available commands:

        Observation Commands (interruptible with Ctrl+C):
          watch [--filter TYPE] [--agent ID]    Stream events from EventBus
          status [--json]                        Show system dashboard
          thread list [--json]                   List all conversation threads
          thread show <id> [--json]              Show thread details
          outcomes ticket <id>                   Show ticket execution history
          outcomes search <query> [--limit N]    Search similar outcomes
          outcomes executor <id> [--limit N]     Show executor performance
          outcomes stats                         Show aggregate statistics

        Session Commands:
          help                Show this help message
          exit, quit          Exit the interactive session

        Action commands coming soon...
        """.trimIndent()

        terminal.writer().println(help)
    }

    fun close() {
        executor.close()
        terminal.close()
    }
}

enum class CommandResult {
    SUCCESS,
    ERROR,
    EXIT,
    INTERRUPTED  // Added for Ctrl+C handling
}
