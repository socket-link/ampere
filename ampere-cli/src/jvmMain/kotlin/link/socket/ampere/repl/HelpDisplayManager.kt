package link.socket.ampere.repl

/**
 * Manages display of help information for the REPL.
 * Centralizes all help text and provides methods for displaying different help sections.
 */
class HelpDisplayManager(
    private val terminalOps: TerminalOperations
) {
    /**
     * Display main help based on terminal width.
     */
    fun displayHelp() {
        val width = terminalOps.getWidth()
        val compact = width < 90

        if (compact) {
            displayCompactHelp()
        } else {
            displayFullHelp()
        }
    }

    /**
     * Display help for a specific command.
     */
    fun displayCommandHelp(commandName: String) {
        when (commandName.lowercase()) {
            "watch", "w" -> displayWatchHelp()
            "ticket" -> displayTicketHelp()
            "message" -> displayMessageHelp()
            "agent" -> displayAgentHelp()
            "status", "s" -> displayStatusHelp()
            "thread", "t" -> displayThreadHelp()
            "outcomes", "o" -> displayOutcomesHelp()
            else -> {
                terminalOps.println(TerminalColors.error("Unknown command: $commandName"))
                terminalOps.println(TerminalColors.info("Type 'help' for all commands"))
            }
        }
    }

    /**
     * Display quick start information.
     */
    fun displayQuickStart() {
        terminalOps.println(TerminalColors.info("  Type 'help' for commands, 'exit' to quit"))
        terminalOps.println(TerminalColors.info("  Press Ctrl+C to interrupt running observations"))
        terminalOps.println()
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

        terminalOps.println(help)
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

            Tip: Start in INSERT mode, press Esc for NORMAL mode shortcuts
        """.trimIndent()

        terminalOps.println(help)
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

        terminalOps.println(help)
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

        terminalOps.println(help)
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

        terminalOps.println(help)
    }

    private fun displayAgentHelp() {
        val help = """
            agent - Interact with agents

            Subcommands:
              wake <id>    Send wake signal to agent

            Examples:
              agent wake agent-pm
        """.trimIndent()

        terminalOps.println(help)
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

        terminalOps.println(help)
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

        terminalOps.println(help)
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

        terminalOps.println(help)
    }
}
