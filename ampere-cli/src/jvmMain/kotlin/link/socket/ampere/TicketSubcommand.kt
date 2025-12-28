package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Subcommand for running the Ticket Creation Test.
 *
 * This is a subcommand of `test`, invoked via `ampere test ticket`.
 *
 * The Ticket Creation Test demonstrates:
 * 1. Loading a complete epic + tasks structure from JSON
 * 2. Validating the JSON structure
 * 3. Creating issues in GitHub using BatchIssueCreator
 * 4. Displaying created issue numbers and URLs
 * 5. Demonstrating parent-child relationships and dependencies
 *
 * The test creates the "Issue Management Tool" epic with 7 subtasks,
 * demonstrating hierarchical issue creation with proper dependency resolution.
 *
 * Usage:
 *   ampere test ticket
 *
 * Prerequisites:
 *   - gh CLI must be installed and authenticated (run: gh auth login)
 *
 * WARNING: This creates REAL issues in the socket-link/ampere repository!
 */
class TicketSubcommand : CliktCommand(
    name = "ticket",
    help = """
        Run the Ticket Creation Test - GitHub issue management demonstration.

        This test demonstrates the Issue Management Tool capabilities:
        - JSON-based issue definition
        - Topological sorting for dependency resolution
        - Parent-child relationship creation
        - Batch issue creation via GitHub CLI

        The test will create:
          - 1 Epic: "Issue Management Tool for ProjectManagerAgent"
          - 7 Tasks: Complete implementation subtasks (AMP-302.1 through AMP-302.7)

        All issues will be created in: socket-link/ampere

        Prerequisites:
          - gh CLI installed and authenticated (gh auth login)

        WARNING: This creates REAL issues! You will be prompted to confirm.

        Examples:
          ampere test ticket              # Run the test
          ampere test ticket --help       # Show this help

        To observe the created issues:
          1. Visit https://github.com/socket-link/ampere/issues
          2. Look for issues created by your GitHub account
          3. Observe parent-child relationships and dependency references
    """.trimIndent()
) {

    override fun run() {
        echo("Starting Ticket Creation Test...")
        echo()

        // Call the IssueCreationTestRunner function
        link.socket.ampere.demo.runIssueCreationTest()
    }
}
