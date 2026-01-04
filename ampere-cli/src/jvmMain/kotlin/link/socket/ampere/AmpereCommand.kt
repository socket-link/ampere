package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Root command for the Ampere CLI.
 *
 * This is the entry point for all CLI operations. It doesn't perform any
 * actions itself, but serves as a container for subcommands.
 *
 * Command hierarchy:
 * - ampere / ampere start: Interactive TUI dashboard
 * - ampere run: Run agents with active work (TUI visualization)
 *   - ampere run --goal <text>: Run agent with custom goal
 *   - ampere run --demo <name>: Run preset demo (jazz, etc.)
 *   - ampere run --issues: Work on GitHub issues
 *   - ampere run --issue <number>: Work on specific issue
 * - ampere watch: Watch live event stream
 * - ampere test: Headless automated tests (CI/validation)
 *   - ampere test jazz: Headless Jazz test
 *   - ampere test ticket: Headless issue test
 * - ampere thread: Manage conversation threads
 * - ampere outcomes: View execution outcomes
 * - ampere issues: Manage GitHub issues
 *
 * Global flags:
 * - --verbose / -v: Show detailed system logs during startup
 */
class AmpereCommand : CliktCommand(
    name = "ampere",
    help = """
        AMPERE - ((Animated Multi-Agent (AniMA)) Protocol (AMP)) Example Runtime Environment

        Usage:
          ampere                    # Start interactive TUI dashboard
          ampere start              # Same as above (explicit)
          ampere run <flags>        # Run agents with active work
          ampere <command>          # Run specific command

        Main Commands:
          start        Interactive TUI dashboard (default)
          run          Run agents with active work (with TUI visualization)
          watch        Stream events in real-time with filtering
          test         Headless automated tests (CI/validation)

        Observation & Management:
          dashboard    Static live-updating dashboard
          thread       View and manage conversation threads
          status       System-wide status overview
          outcomes     View execution outcomes and learnings

        GitHub Integration:
          issues       Manage GitHub issues (create from JSON)
          work         Autonomously work on GitHub issues (headless)
          respond      Respond to agent human input requests

        Other:
          interactive  REPL session for direct interaction
          help         Show comprehensive help message

        Interactive TUI Controls (start/run commands):
          Viewing Modes:
            d          Dashboard - System vitals, agent status, recent events
            e          Event Stream - Filtered stream of significant events
            m          Memory Operations - Knowledge recall/storage patterns
            1-9        Agent Focus - Detailed view of specific agent

          Options:
            v          Toggle verbose mode (show/hide logs)
            h  or  ?   Toggle help screen
            :          Command mode - Issue commands to the system
            ESC        Close help / Cancel command mode
            q  or Ctrl+C   Exit

        Command Mode (press ':' in TUI):
            :goal <text>  Start agent with goal
            :help         Show available commands
            :agents       List all active agents
            :ticket <id>  Show ticket details
            :thread <id>  Show conversation thread
            :quit         Exit TUI

        Examples:
          # Interactive TUI
          ampere                              # Start TUI dashboard
          ampere start                        # Same as above

          # Run agents with TUI visualization
          ampere run --goal "Implement FizzBuzz"
          ampere run --demo jazz              # Interactive Jazz demo
          ampere run --issues                 # Work on GitHub issues
          ampere run --issue 42               # Work on specific issue

          # Headless tests (CI/validation)
          ampere test jazz                    # Headless Jazz test
          ampere test ticket                  # Headless issue test

          # Observation
          ampere watch --verbose              # Watch all events
          ampere watch --filter TaskCreated   # Watch specific events

          # Management
          ampere thread list                  # List conversation threads
          ampere outcomes stats               # View learning statistics
          ampere issues create -f epic.json   # Create issues from file

          # Legacy headless work mode
          ampere work                         # Work on issues (headless)
          ampere work --continuous            # Keep working (headless)

        For more information on specific commands:
          ampere <command> --help
    """.trimIndent()
) {
    override fun run() = Unit
}
