package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Display help information about AMPERE commands and the interactive dashboard.
 */
class HelpCommand : CliktCommand(
    name = "help",
    help = "Display help information about AMPERE commands"
) {
    override fun run() {
        println("""
            AMPERE - Animated Multi-Agent Prompting Environment

            Usage:
              ampere                    # Start interactive dashboard (default)
              ampere start              # Start interactive dashboard (explicit)
              ampere <command>          # Run specific command

            Main Commands:
              start        Interactive multi-modal dashboard (default)
              watch        Stream events in real-time with filtering
              dashboard    Static live-updating dashboard
              interactive  REPL session for direct interaction
              thread       View and manage conversation threads
              status       System-wide status overview
              outcomes     View execution outcomes and learnings
              help         Show this help message

            Interactive Dashboard Controls:
              Viewing Modes:
                d          Dashboard - System vitals, agent status, recent events
                e          Event Stream - Filtered stream of significant events
                m          Memory Operations - Knowledge recall/storage patterns
                1-9        Agent Focus - Detailed view of specific agent

              Options:
                v          Toggle verbose mode (show/hide routine events)
                h  or  ?   Toggle help screen
                :          Command mode - Issue commands to the system
                ESC        Close help / Cancel command mode
                q  or Ctrl+C   Exit dashboard

            Command Mode (press ':'):
                :help         Show available commands
                :agents       List all active agents
                :ticket <id>  Show ticket details
                :thread <id>  Show conversation thread
                :quit         Exit dashboard

            Examples:
              ampere                              # Start dashboard
              ampere watch --verbose              # Watch all events
              ampere watch --filter TaskCreated   # Watch task events only
              ampere thread list                  # List conversation threads
              ampere outcomes stats               # View learning statistics

            For more information on specific commands, use:
              ampere <command> --help
        """.trimIndent())
    }
}
