package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import link.socket.ampere.util.LoggingConfiguration

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
        AMPERE Interactive CLI

        Framework hierarchy:
        • AniMA - Animated Multi-Agent prompting framework
        • AMP - AniMA Model Protocol specification
        • AMPERE - AMP Example Runtime Environment (this implementation)

        AMPERE provides CLI tools for observing and affecting an autonomous
        multi-agent environment with built-in coordination, memory, and learning.

        Commands:
        • watch       - Stream events in real-time (with filtering and grouping)
        • dashboard  - Live-updating dashboard view of system state
        • thread      - View and manage conversation threads
        • status      - System-wide status overview
        • outcomes   - Query execution outcomes and learnings
    """.trimIndent()
) {
    override fun run() = Unit
}
