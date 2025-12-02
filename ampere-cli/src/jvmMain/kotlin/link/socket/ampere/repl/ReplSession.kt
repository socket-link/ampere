package link.socket.ampere.repl

import java.nio.file.Paths
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

/**
 * Manages an interactive REPL session for the AMPERE CLI.
 *
 * The REPL session maintains a persistent environment where multiple
 * commands can be executed sequentially without restarting the environment.
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
            
                  ⚡──○──⚡
               ⚡──○──⚡──○──⚡          AMPERE Interactive Shell v0.1.0
            ⚡──○──⚡──○──⚡──○──⚡     Autonomous multi-agent coordination
               ⚡──○──⚡──○──⚡
                  ⚡──○──⚡
        """.trimIndent()

        terminal.writer().println(banner)
        terminal.writer().println()

        // Display initial system status
        displaySystemStatus()
        terminal.writer().println()
        displayQuickStart()
    }

    private fun displayQuickStart() {
        terminal.writer().println(TerminalColors.info("  Type 'help' for commands, 'exit' to quit"))
        terminal.writer().println(TerminalColors.info("  Press Ctrl+C to interrupt running observations"))
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
            "help", "?" -> {
                if (args.isNotBlank()) {
                    displayCommandHelp(args.trim())
                } else {
                    displayHelp()
                }
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
        val width = terminal.width
        val compact = width < 90

        if (compact) {
            displayCompactHelp()
        } else {
            displayFullHelp()
        }
    }

    private fun displayCompactHelp() {
        val help = """
            AMPERE - Command Reference

            ─── OBSERVE (Ctrl+C to stop) ───
            w [-f TYPE] [-a ID]    Events
            s                      Status
            t list | show <id>     Threads
            o ticket|search|stats  Outcomes

            ─── ACT ───
            ticket create "TITLE" [-p PRI] [-d "DESC"]
                   assign <id> <agent>
                   status <id> <STATUS>

            message post <thread> "TEXT" [-s ID]
                    create-thread -t "TITLE" --participants A,B

            agent wake <id>

            ─── FLAGS ───
            -f, --filter       Event type
            -a, --agent        Agent ID
            -p, --priority     Ticket priority
            -d, --description  Description
            -s, --sender       Message sender
            -t, --title        Thread title
            -n, --limit        Result limit
            -h, --help         Show help

            ─── KEYS ───
            Ctrl+C      Exit
            Ctrl+D      Stop/Exit
            Ctrl+L      Clear
            ↑/↓         History

            Type 'help <command>' for details
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayFullHelp() {
        val help = """
            AMPERE Interactive Shell - Command Reference

            ═══ OBSERVATION COMMANDS ═══
            watch [-f TYPE] [-a AGENT]        Stream events
            status                            System dashboard
            thread list                       List threads
            thread show <id>                  Thread details
            outcomes ticket <id>              Ticket history
            outcomes search <query> [-n N]    Search outcomes
            outcomes executor <id> [-n N]     Executor stats
            outcomes stats                    Aggregate stats

            ═══ ACTION COMMANDS ═══
            ticket create "TITLE" [-p PRIORITY] [-d "DESC"]
                                              Create ticket
            ticket assign <id> <agent>        Assign ticket
            ticket status <id> <STATUS>       Update status

            message post <thread> "TEXT" [-s ID]
                                              Post message
            message create-thread -t "TITLE" --participants A,B
                                              New thread

            agent wake <id>                   Wake agent

            ═══ FLAG ALIASES ═══
            -f, --filter       Event type
            -a, --agent        Agent ID
            -p, --priority     Ticket priority (HIGH|MEDIUM|LOW|CRITICAL)
            -d, --description  Ticket description
            -s, --sender       Message sender
            -t, --title        Thread title
            -n, --limit        Result limit
            -h, --help         Show help

            ═══ COMMAND ALIASES ═══
            w → watch     s → status    t → thread
            o → outcomes  q → quit      ? → help

            ═══ SESSION COMMANDS ═══
            help, ?             Show this help message
            help <command>      Show command-specific help
            exit, quit, q       Exit the session (or Ctrl+D)
            clear               Clear screen

            ═══ KEYBINDINGS ═══
            Ctrl+C          Exit REPL
            Ctrl+D          Stop observation / Exit if idle
            Ctrl+L          Clear screen
            ↑/↓             Command history
            Tab             Command completion

            Type 'help <command>' for detailed command help
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayCommandHelp(commandName: String) {
        when (commandName.lowercase()) {
            "watch", "w" -> displayWatchHelp()
            "ticket" -> displayTicketHelp()
            "message" -> displayMessageHelp()
            "agent" -> displayAgentHelp()
            "status", "s" -> displayStatusHelp()
            "thread", "t" -> displayThreadHelp()
            "outcomes", "o" -> displayOutcomesHelp()
            else -> {
                terminal.writer().println(TerminalColors.error("Unknown command: $commandName"))
                terminal.writer().println(TerminalColors.info("Type 'help' for all commands"))
            }
        }
    }

    private fun displayWatchHelp() {
        val help = """
            watch - Stream events in real-time

            Usage: watch [-f TYPE] [-a AGENT]

            Flags:
              -f, --filter TYPE    Event type filter (repeatable)
              -a, --agent ID       Agent ID filter (repeatable)
              -h, --help           Show this help

            Examples:
              watch -f TaskCreated -f MessagePosted
              watch -a agent-pm
              watch -f TicketStatusChanged -a agent-dev

            Controls:
              Ctrl+C     Stop watching
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayTicketHelp() {
        val help = """
            ticket - Manage tickets

            Subcommands:
              create "TITLE" [-p PRI] [-d "DESC"]    Create ticket
              assign <id> <agent>                    Assign ticket
              status <id> <STATUS>                   Update status

            Flags:
              -p, --priority     HIGH|MEDIUM|LOW|CRITICAL
              -d, --description  Ticket description

            Examples:
              ticket create "Add auth" -p HIGH
              ticket assign TKT-123 agent-dev
              ticket status TKT-123 InProgress
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayMessageHelp() {
        val help = """
            message - Manage messages and threads

            Subcommands:
              post <thread> "TEXT" [-s ID]           Post message
              create-thread -t "TITLE" --participants A,B
                                                     Create thread

            Flags:
              -s, --sender       Message sender (default: human)
              -t, --title        Thread title
              --participants     Comma-separated IDs

            Examples:
              message post thread-123 "Hello"
              message create-thread -t "Planning" --participants agent-pm,agent-dev
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayAgentHelp() {
        val help = """
            agent - Interact with agents

            Subcommands:
              wake <id>    Send wake signal to agent

            Examples:
              agent wake agent-pm
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayStatusHelp() {
        val help = """
            status - Show system dashboard

            Usage: status

            Displays:
              - Active threads with escalations
              - Ticket breakdown by status
              - High-priority items
              - System alerts
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayThreadHelp() {
        val help = """
            thread - View conversation threads

            Subcommands:
              list         List all active threads
              show <id>    Show thread details

            Examples:
              thread list
              thread show thread-123
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayOutcomesHelp() {
        val help = """
            outcomes - View execution outcomes

            Subcommands:
              ticket <id>            Ticket execution history
              search <query> [-n N]  Search similar outcomes
              executor <id> [-n N]   Executor performance
              stats                  Aggregate statistics

            Flags:
              -n, --limit    Result limit

            Examples:
              outcomes ticket TKT-123
              outcomes search "authentication" -n 10
              outcomes executor agent-dev
              outcomes stats
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
