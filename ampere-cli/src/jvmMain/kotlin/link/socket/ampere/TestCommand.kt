package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

/**
 * Parent command for all test runners.
 *
 * This command provides a unified entry point for running various end-to-end tests
 * and demonstrations of AMPERE's capabilities.
 *
 * Available tests:
 * - jazz: Autonomous agent demonstration (CodeWriterAgent + Fibonacci task)
 * - ticket: Issue creation demonstration (GitHub issue management)
 *
 * Usage:
 *   ampere test <test-name>
 *
 * Examples:
 *   ampere test jazz      # Run Jazz Test (autonomous agent demo)
 *   ampere test ticket    # Run Ticket Creation Test (GitHub issues)
 */
class TestCommand : CliktCommand(
    name = "test",
    help = """
        Run end-to-end tests and demonstrations.

        Available tests:
          jazz      Run Jazz Test - Autonomous agent demonstration
          ticket    Run Ticket Creation Test - GitHub issue management

        Each test demonstrates different capabilities of the AMPERE system.

        Examples:
          ampere test jazz              # Autonomous code writing demo
          ampere test ticket            # GitHub issue creation demo
          ampere test jazz --help       # Show jazz test options
          ampere test ticket --help     # Show ticket test options
    """.trimIndent()
) {
    init {
        subcommands(
            JazzSubcommand(),
            TicketSubcommand()
        )
    }

    override fun run() {
        // Parent command doesn't do anything on its own
        // User must specify a subcommand (jazz or ticket)
    }
}
