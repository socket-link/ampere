package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Root command for the Ampere CLI.
 *
 * This is the entry point for all CLI operations. It doesn't perform any
 * actions itself, but serves as a container for subcommands like watch,
 * thread, and status.
 *
 * Command hierarchy:
 * - ampere watch: Watch live event stream
 * - ampere thread: Manage conversation threads
 *   - ampere thread list: List all threads
 *   - ampere thread show: Show thread details
 * - ampere status: View system-wide dashboard
 * - ampere outcomes: View execution outcomes and accumulated experience
 *   - ampere outcomes ticket: Show execution history for a specific ticket
 *   - ampere outcomes search: Find outcomes similar to a description
 *   - ampere outcomes executor: Show outcomes for a specific executor
 *   - ampere outcomes stats: Show aggregate outcome statistics
 *
 * Global flags:
 * - --verbose / -v: Show detailed system logs during startup
 */
class AmpereCommand : CliktCommand(
    name = "ampere",
    help = """
        AMPERE - Animated Multi-Agent Prompting Environment

        Usage:
          ampere                    # Start interactive dashboard (default)
          ampere start              # Start interactive dashboard (explicit)
          ampere <command>          # Run specific command

        Main Commands:
          start        Interactive multi-modal dashboard (default)
          help         Show comprehensive help message
          watch        Stream events in real-time with filtering
          dashboard    Static live-updating dashboard
          interactive  REPL session for direct interaction
          thread       View and manage conversation threads
          status       System-wide status overview
          outcomes     View execution outcomes and learnings
          respond      Respond to agent human input requests

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

        Command Mode (press ':' in dashboard):
            :help         Show available commands
            :agents       List all active agents
            :ticket <id>  Show ticket details
            :thread <id>  Show conversation thread
            :quit         Exit dashboard

        Examples:
          ampere                              # Start dashboard
          ampere help                         # Show this help
          ampere watch --verbose              # Watch all events
          ampere watch --filter TaskCreated   # Watch task events only
          ampere thread list                  # List conversation threads
          ampere outcomes stats               # View learning statistics
          ampere respond abc-123 "Approved"   # Respond to human input request

        For more information on specific commands:
          ampere <command> --help
    """.trimIndent()
) {
    override fun run() = Unit
}
