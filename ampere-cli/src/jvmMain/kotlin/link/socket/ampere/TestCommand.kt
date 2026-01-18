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
 *   ampere run --demo jazz
 *
 * Available tests:
 * - jazz: Autonomous agent test (CodeWriterAgent + task-create CLI command) - headless
 * - ticket: Issue creation test (GitHub issue management) - headless
 *
 * Usage:
 *   ampere test <test-name>
 *
 * Examples:
 *   ampere test jazz      # Run Jazz Test headlessly (for CI/validation)
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
          jazz      Headless autonomous agent test (task-create CLI command)
          ticket    Headless GitHub issue creation test

        These tests validate AMPERE functionality and are designed for
        automated environments, not interactive exploration.

        Examples:
          ampere test jazz              # Headless agent test (CI/validation)
          ampere test ticket            # Headless issue test
          ampere test jazz --help       # Show test options

        For interactive demos:
          ampere run --demo jazz        # Interactive Jazz demo with TUI
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
