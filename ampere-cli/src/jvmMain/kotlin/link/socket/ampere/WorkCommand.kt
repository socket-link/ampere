package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.repl.TerminalFactory

/**
 * Command to trigger autonomous work by CodeAgent on GitHub issues.
 *
 * This command enables the CodeAgent to autonomously:
 * 1. Discover available issues (assigned or with 'code' label)
 * 2. Claim an issue and update status to IN_PROGRESS
 * 3. Create an implementation plan
 * 4. Execute the plan (write code, create branch, commit, push)
 * 5. Create a pull request with proper formatting
 * 6. Update issue status to IN_REVIEW
 *
 * Usage:
 *   ampere work                                    # Work on next available issue
 *   ampere work --repo owner/repo                  # Specify repository
 *   ampere work --issue 123                        # Work on specific issue
 *   ampere work --continuous                       # Keep working on issues
 *   ampere work --labels code,backend              # Filter by labels
 *   ampere work --dry-run                          # Validate without executing
 *
 * Examples:
 *   # Work on next assigned issue
 *   ampere work
 *
 *   # Work on all available issues continuously
 *   ampere work --continuous
 *
 *   # Work on specific issue in a repository
 *   ampere work --repo myorg/myrepo --issue 42
 *
 *   # Preview what would be done without executing
 *   ampere work --dry-run
 */
class WorkCommand(
    private val contextProvider: () -> AmpereContext
) : CliktCommand(
    name = "work",
    help = "Autonomously work on GitHub issues"
) {
    private val terminal = TerminalFactory.createTerminal()

    private val repository by option(
        "--repo", "-r",
        help = "GitHub repository in format owner/repo (e.g., 'myorg/myrepo')"
    )

    private val issueNumber by option(
        "--issue", "-i",
        help = "Specific issue number to work on"
    ).int()

    private val labels by option(
        "--labels", "-l",
        help = "Filter issues by labels (comma-separated)"
    ).multiple()

    private val continuous by option(
        "--continuous", "-c",
        help = "Keep working on issues until none available"
    ).flag(default = false)

    private val dryRun by option(
        "--dry-run",
        help = "Show what would be done without executing"
    ).flag(default = false)

    private val maxIssues by option(
        "--max-issues",
        help = "Maximum number of issues to process in continuous mode"
    ).int().default(10)

    override fun run() = runBlocking {
        val context = contextProvider()

        terminal.println(bold(cyan("⚡ AMPERE Autonomous Work Mode")))
        terminal.println()

        // Show configuration
        terminal.println(bold("Configuration:"))
        terminal.println("  Repository: ${repository ?: dim("(from git remote)")}")
        terminal.println("  Issue: ${issueNumber?.toString() ?: dim("(auto-select)")}")
        terminal.println("  Labels: ${labels.joinToString(", ").ifEmpty { dim("(none)") }}")
        terminal.println("  Mode: ${if (continuous) yellow("continuous") else green("single")}")
        terminal.println("  Max Issues: ${if (continuous) maxIssues.toString() else dim("(N/A)")}")
        terminal.println("  Dry Run: ${if (dryRun) yellow("yes") else green("no")}")
        terminal.println()

        if (dryRun) {
            terminal.println(yellow("⚠ Dry run mode - no changes will be made"))
            terminal.println()

            // Show what would happen
            val issues = context.codeAgent.queryAvailableIssues()
            terminal.println("Would work on ${issues.size} available issue(s)")
            issues.take(5).forEach { issue ->
                terminal.println("  #${issue.number}: ${issue.title}")
            }
            return@runBlocking
        }

        if (continuous) {
            // Start work loop
            context.startAutonomousWork()

            terminal.println(green("Autonomous work mode started"))
            terminal.println("Press Ctrl+C to stop")

            // Wait for user interrupt
            Runtime.getRuntime().addShutdownHook(Thread {
                runBlocking { context.stopAutonomousWork() }
            })

            // Block until interrupted
            while (context.autonomousWorkLoop.isRunning.value) {
                kotlinx.coroutines.delay(1000)
            }
        } else {
            // Work on single issue
            val issues = context.codeAgent.queryAvailableIssues()

            if (issues.isEmpty()) {
                terminal.println(yellow("No available issues found"))
                return@runBlocking
            }

            val issue = issueNumber?.let { num ->
                issues.find { it.number == num }
            } ?: issues.first()

            terminal.println("Working on issue #${issue.number}: ${issue.title}")

            val claimed = context.codeAgent.claimIssue(issue.number)
            if (claimed.isFailure) {
                terminal.println(red("Failed to claim issue: ${claimed.exceptionOrNull()?.message}"))
                return@runBlocking
            }

            val result = context.codeAgent.workOnIssue(issue)
            if (result.isSuccess) {
                terminal.println(green("✓ Successfully completed issue #${issue.number}"))
            } else {
                terminal.println(red("✗ Failed: ${result.exceptionOrNull()?.message}"))
            }
        }
    }
}
