package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

/**
 * Parent command for headless automated tests.
 *
 * This command provides a unified entry point for running headless validation
 * tests of AMPERE's capabilities. These tests are intended for CI/CD pipelines
 * and automated validation, not interactive use.
 *
 * For interactive demonstrations with visual feedback, use the 'run' command instead:
 *   ampere run --demo agent
 *
 * Available tests:
 * - agent: Autonomous agent test (CodeWriterAgent + PROPEL cognitive cycle) - headless
 * - ticket: Issue creation test (GitHub issue management) - headless
 *
 * Usage:
 *   ampere test <test-name>
 *
 * Examples:
 *   ampere test agent     # Run autonomous agent test headlessly (for CI/validation)
 *   ampere test ticket    # Run Ticket Creation Test headlessly
 */
class TestCommand : CliktCommand(
    name = "test",
    help = """
        Run headless automated tests for validation.

        This command runs tests in headless mode without interactive UI,
        suitable for CI/CD pipelines and automated validation.

        For interactive demonstrations with visual feedback, use:
          ampere run --demo <name>

        Available tests:
          agent     Headless autonomous agent test (PROPEL cognitive cycle)
          ticket    Headless GitHub issue creation test

        These tests validate AMPERE functionality and are designed for
        automated environments, not interactive exploration.

        Examples:
          ampere test agent             # Headless agent test (CI/validation)
          ampere test ticket            # Headless issue test
          ampere test agent --help      # Show test options

        For interactive demos:
          ampere demo                   # Interactive demo with TUI
    """.trimIndent()
) {
    init {
        subcommands(
            AgentSubcommand(),
            TicketSubcommand()
        )
    }

    override fun run() {
        // Parent command doesn't do anything on its own
        // User must specify a subcommand (agent or ticket)
    }
}
