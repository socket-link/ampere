package link.socket.ampere.repl

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.AmpereContext
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp
import sun.misc.Signal
import java.nio.file.Paths

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

    private val historyFile = Paths.get(
        System.getProperty("user.home"),
        ".ampere",
        "history"
    )

    private val reader: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .variable(LineReader.HISTORY_FILE, historyFile)
        .completer(AmpereCompleter(context))
        .build()

    private val executor = CommandExecutor(terminal)

    // Add registry for observation commands
    private val observationCommands = ObservationCommandRegistry(
        context,
        terminal,
        executor
    )

    // Add registry for action commands
    private val actionCommands = ActionCommandRegistry(
        context,
        terminal
    )

    // Command aliases for convenience
    private val aliases = mapOf(
        "w" to "watch",
        "s" to "status",
        "t" to "thread",
        "o" to "outcomes",
        "q" to "quit",
        "?" to "help"
    )

    init {
        // Ensure history directory exists
        historyFile.parent.toFile().mkdirs()

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
                val line = try {
                    reader.readLine("ampere> ")
                } catch (e: EndOfFileException) {
                    // Ctrl+D pressed
                    terminal.writer().println()
                    terminal.writer().println("Goodbye! Shutting down environment...")
                    break
                }

                if (line.isNullOrBlank()) {
                    continue
                }

                // Handle special commands first
                when (line.trim().lowercase()) {
                    "clear" -> {
                        terminal.puts(InfoCmp.Capability.clear_screen)
                        terminal.flush()
                        continue
                    }
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
                terminal.writer().println(TerminalColors.error(e.message ?: "Unknown error"))
            }
        }
    }

    private suspend fun executeCommand(input: String): CommandResult {
        // Expand aliases first
        val expandedInput = expandAliases(input)

        // First check if it's an observation command
        val observationResult = observationCommands.executeIfMatches(expandedInput)
        if (observationResult != null) {
            return observationResult
        }

        // Then check if it's an action command
        val actionResult = actionCommands.executeIfMatches(expandedInput)
        if (actionResult != null) {
            return actionResult
        }

        // Otherwise check built-in REPL commands
        val parts = expandedInput.split(" ", limit = 2)
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
                terminal.writer().println(TerminalColors.error("Unknown command: $command"))
                terminal.writer().println(TerminalColors.info("Type 'help' for available commands"))
                CommandResult.ERROR
            }
        }
    }

    /**
     * Expand command aliases to their full forms.
     */
    private fun expandAliases(input: String): String {
        val parts = input.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val rest = parts.getOrNull(1) ?: ""

        val expandedCommand = aliases[command] ?: command

        return if (rest.isEmpty()) {
            expandedCommand
        } else {
            "$expandedCommand $rest"
        }
    }

    private fun displayHelp() {
        val help = """
        Available commands:

        Observation Commands (interruptible with Ctrl+C):
          watch, w [--filter TYPE] [--agent ID]  Stream events from EventBus
          status, s [--json]                     Show system dashboard
          thread, t list [--json]                List all conversation threads
          thread, t show <id> [--json]           Show thread details
          outcomes, o ticket <id>                Show ticket execution history
          outcomes, o search <query> [--limit N] Search similar outcomes
          outcomes, o executor <id> [--limit N]  Show executor performance
          outcomes, o stats                      Show aggregate statistics

        Action Commands (affect the substrate):
          ticket create "TITLE" [--priority P] [--description "DESC"] [--type TYPE]
                                                 Create new ticket (P: LOW|MEDIUM|HIGH|CRITICAL)
          ticket assign TICKET_ID AGENT_ID       Assign ticket to agent
          ticket status TICKET_ID STATUS         Update ticket status

          message post THREAD_ID "CONTENT" [--sender ID]
                                                 Post message to thread
          message create-thread --title "TITLE" --participants ID1,ID2
                                                 Create new thread

          agent wake AGENT_ID                    Send wake signal to agent

        Session Commands:
          help, ?             Show this help message
          exit, quit, q       Exit the interactive session (or press Ctrl+D)
          clear               Clear the screen

        Tips:
          - Press ↑/↓ to navigate command history
          - Press Tab for command completion
          - Press Ctrl+C to interrupt running observations
          - Press Ctrl+L to clear screen
          - Press Ctrl+D to exit
        """.trimIndent()

        terminal.writer().println(help)
    }

    fun close() {
        // JLine3 automatically saves history when the reader is closed
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
