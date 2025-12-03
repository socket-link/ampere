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
        .also { installCustomKeyBindings(it) }

    private val executor = CommandExecutor(terminal)
    private val statusBar = StatusBar(terminal)
    private val modeManager = ModeManager()
    private val filterCycler = EventFilterCycler()

    // Add registry for observation commands
    private val observationCommands = ObservationCommandRegistry(
        context,
        terminal,
        executor,
        statusBar,
        filterCycler
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
     * Install custom key bindings for Ctrl+L and Ctrl+Space to clear screen.
     */
    private fun installCustomKeyBindings(reader: LineReader) {
        // Create a custom widget for clearing the screen
        val clearScreenWidget = org.jline.reader.Widget {
            clearScreen()
            true  // Return true to indicate the widget handled the key
        }

        reader.getWidgets()["clear-screen-custom"] = clearScreenWidget

        // Bind Ctrl+L (already bound by default in JLine, but we override it)
        reader.getKeyMaps()["main"]?.bind(
            org.jline.reader.Reference("clear-screen-custom"),
            "\u000C"
        )

        // Bind Ctrl+Space
        reader.getKeyMaps()["main"]?.bind(
            org.jline.reader.Reference("clear-screen-custom"),
            "\u0000"
        )
    }

    /**
     * Install SIGINT (Ctrl+C) handler for hard exit.
     * In vim-inspired modal system, Ctrl+C is emergency exit.
     */
    private fun installSignalHandler() {
        Signal.handle(Signal("INT")) { signal ->
            terminal.writer().println("\n${TerminalColors.dim("Emergency exit!")}")
            kotlin.system.exitProcess(0)
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
                statusBar.render(
                    modeManager.getCurrentMode(),
                    if (modeManager.getCurrentMode() == Mode.OBSERVING) filterCycler.current() else null
                )

                val line = try {
                    readLineWithModeHandling()
                } catch (e: EndOfFileException) {
                    // Ctrl+D handling
                    handleCtrlD()
                    continue
                } catch (e: EmergencyExitException) {
                    // Double-Esc
                    terminal.writer().println("\n${TerminalColors.warning("Emergency exit!")}")
                    break
                }

                if (line == null) continue

                // Execute command in cancellable context
                val result = runBlocking {
                    executeCommand(line)
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

    private fun readLineWithModeHandling(): String? {
        return when (modeManager.getCurrentMode()) {
            Mode.NORMAL -> readNormalModeInput()
            Mode.INSERT -> readInsertModeInput()
            Mode.OBSERVING -> readObservingModeInput()
        }
    }

    private fun readNormalModeInput(): String? {
        // In normal mode, read single character using terminal reader
        val key = terminal.reader().read()

        return when (key.toChar()) {
            'w' -> "watch"
            's' -> "status"
            't' -> "thread list"
            'o' -> "outcomes stats"
            'h', '?' -> "help"
            'c' -> "clear"
            'q' -> "exit"
            // Number keys for quick dashboard access
            '1' -> "status"
            '2' -> "thread list"
            '3' -> "outcomes stats"
            '4' -> "watch"
            '5' -> "outcomes ticket"  // Most recent would need implementation
            '\u000C' -> {  // Ctrl+L
                clearScreen()
                null
            }
            '\u0000' -> {  // Ctrl+Space
                clearScreen()
                null
            }
            'i', 'a' -> {
                modeManager.enterInsertMode()
                null  // Don't execute, just switch mode
            }
            '\u001B' -> {  // Escape key - might be escape sequence
                // Peek ahead to see if this is an escape sequence
                if (terminal.reader().peek(100) > 0) {
                    // Read the next character
                    val next = terminal.reader().read()
                    if (next == '['.code) {
                        // This is a CSI sequence, ignore it
                        return null
                    } else if (next >= '1'.code && next <= '5'.code) {
                        // Ctrl+number sequence (Alt+number on some terminals)
                        return when (next.toChar()) {
                            '1' -> "status"
                            '2' -> "thread list"
                            '3' -> "outcomes stats"
                            '4' -> "watch"
                            '5' -> "outcomes ticket"
                            else -> null
                        }
                    }
                }
                // Regular escape handling
                if (modeManager.handleEscape()) {
                    throw EmergencyExitException()
                }
                null
            }
            '\n', '\r' -> {
                // Enter in normal mode = switch to insert
                modeManager.enterInsertMode()
                null
            }
            else -> {
                terminal.writer().println(TerminalColors.dim("Unknown key. Press ? for help."))
                null
            }
        }
    }

    /**
     * Clear the terminal screen.
     */
    private fun clearScreen() {
        terminal.puts(InfoCmp.Capability.clear_screen)
        terminal.flush()
    }

    private fun readInsertModeInput(): String? {
        val line = reader.readLine("")

        // Check for empty enter during observation
        if (line.isBlank() && executor.isExecuting()) {
            executor.interrupt()
            modeManager.enterInsertMode()
            terminal.writer().println(TerminalColors.dim("Stopped observation"))
            return null
        }

        return line.trim().takeIf { it.isNotBlank() }
    }

    private fun readObservingModeInput(): String? {
        // During observation, check for Enter (stop) or Ctrl+E (cycle filter)
        val key = terminal.reader().read()

        return when (key.toChar()) {
            '\n', '\r' -> {
                // Empty enter stops observation
                executor.interrupt()
                modeManager.enterInsertMode()
                terminal.writer().println(TerminalColors.dim("Stopped observation"))
                null
            }
            '\u0005' -> {  // Ctrl+E
                // Cycle filter
                val newFilter = filterCycler.cycle()
                terminal.writer().println(
                    TerminalColors.info("Filter: ${filterCycler.currentDisplayName()}")
                )
                null
            }
            else -> null
        }
    }

    private fun handleCtrlD() {
        when (modeManager.getCurrentMode()) {
            Mode.OBSERVING -> {
                // Stop observation, return to insert mode
                executor.interrupt()
                modeManager.enterInsertMode()
                terminal.writer().println()
                terminal.writer().println(TerminalColors.dim("Disconnected from observation"))
            }
            else -> {
                // Exit session
                terminal.writer().println()
                terminal.writer().println("Goodbye! Shutting down environment...")
                kotlin.system.exitProcess(0)
            }
        }
    }

    private suspend fun executeCommand(input: String): CommandResult {
        // Handle special commands
        when (input.lowercase()) {
            "clear", ".clear" -> {
                terminal.puts(InfoCmp.Capability.clear_screen)
                terminal.flush()
                return CommandResult.SUCCESS
            }
            ".test-colors", ".colors" -> {
                testColorOutput()
                return CommandResult.SUCCESS
            }
        }

        // Expand aliases
        val expandedInput = expandAliases(input)

        // Try observation commands first
        val observationResult = observationCommands.executeIfMatches(expandedInput)
        if (observationResult != null) {
            if (observationResult == CommandResult.SUCCESS) {
                modeManager.setMode(Mode.OBSERVING)
            }
            return observationResult
        }

        // Try action commands
        val actionResult = actionCommands.executeIfMatches(expandedInput)
        if (actionResult != null) {
            return actionResult
        }

        // Check built-in commands
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
                    terminal.writer().println("Running for 30 seconds... Press Enter to interrupt")
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
     * Test color output to verify terminal color support.
     */
    private fun testColorOutput() {
        terminal.writer().println()
        terminal.writer().println("Color Support Test:")
        terminal.writer().println("━".repeat(50))
        terminal.writer().println()

        // Display all color tests
        TerminalColors.getColorTests().forEach { (label, coloredText) ->
            terminal.writer().println("  $label: $coloredText")
        }

        terminal.writer().println()
        terminal.writer().println("If you see colors above, your terminal supports ANSI codes.")
        terminal.writer().println("Colors enabled: ${TerminalColors.enabled}")
        terminal.writer().println()
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

            ─── DEBUG ───
            .test-colors    Test color support

            Type 'help <command>' for details
        """.trimIndent()

        terminal.writer().println(help)
    }

    private fun displayFullHelp() {
        val help = """
            AMPERE Interactive Shell - Vim-Inspired Modal Interface

            ═══ MODES ═══
            NORMAL    Single-key commands (press Esc)
                      w=watch s=status t=threads o=outcomes
                      h=help c=clear q=quit
                      i/a/Enter = switch to INSERT mode

            INSERT    Full command entry (default mode)
                      Tab completion, history, aliases
                      Esc = switch to NORMAL mode

            OBSERVING Active observation (watch, etc.)
                      Enter = stop observation
                      Ctrl+E = cycle event filter
                      Ctrl+D = disconnect

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

            ═══ KEYBINDINGS ═══
            Ctrl+C      Emergency exit (hard quit)
            Ctrl+D      Stop observation / Exit if idle
            Ctrl+E      Cycle event filter (during watch)
            Esc Esc     Emergency exit (double-tap)
            Enter       Context-sensitive (stop observation if running)
            ↑/↓         Command history
            Tab         Command completion

            ═══ ALIASES ═══
            w → watch     s → status    t → thread
            o → outcomes  q → quit      ? → help

            ═══ DEBUG COMMANDS ═══
            .test-colors    Verify terminal color support

            Tip: Start in INSERT mode, press Esc for NORMAL mode shortcuts
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

/**
 * Exception thrown when user triggers emergency exit (double-tap Esc).
 */
class EmergencyExitException : Exception()
