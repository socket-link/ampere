package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.integrations.issues.BatchIssueCreator
import link.socket.ampere.integrations.issues.github.GitHubCliProvider
import link.socket.ampere.repl.TerminalFactory

/**
 * Root command for managing GitHub issues.
 *
 * Provides access to issue creation, listing, and management operations.
 */
class IssuesCommand : CliktCommand(
    name = "issues",
    help = "Manage GitHub issues",
) {
    init {
        subcommands(
            CreateIssuesCommand(),
        )
    }

    override fun run() = Unit
}

/**
 * Create issues from JSON file or stdin.
 *
 * Accepts BatchIssueCreateRequest JSON from file or stdin and creates
 * the issues in batch with proper dependency ordering and parent-child
 * relationships.
 *
 * Examples:
 * ```bash
 * # Validate without creating
 * ampere issues create --from-file pm-agent-epic.json --dry-run
 *
 * # Create from file
 * ampere issues create -f .ampere/issues/pm-agent-epic.json
 *
 * # Create from stdin (useful for piping)
 * cat pm-agent-epic.json | ampere issues create --stdin
 * ```
 */
class CreateIssuesCommand : CliktCommand(
    name = "create",
    help = "Create issues from JSON file or stdin",
) {
    private val fromFile by option("--from-file", "-f", help = "JSON file path")
        .path(mustExist = true, canBeDir = false)

    private val fromStdin by option("--stdin", help = "Read JSON from stdin")
        .flag(default = false)

    private val dryRun by option("--dry-run", help = "Validate JSON without creating issues")
        .flag(default = false)

    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()

        // Read JSON from file or stdin
        val json = when {
            fromFile != null -> fromFile!!.toFile().readText()
            fromStdin -> generateSequence(::readLine).joinToString("\n")
            else -> {
                terminal.println(red("Error: Must specify --from-file or --stdin"))
                terminal.println("Usage: ampere issues create --from-file <path>")
                terminal.println("       ampere issues create --stdin < file.json")
                return@runBlocking
            }
        }

        // Parse JSON
        val request = try {
            DEFAULT_JSON.decodeFromString<BatchIssueCreateRequest>(json)
        } catch (e: Exception) {
            terminal.println(red("Error parsing JSON: ${e.message}"))
            terminal.println()
            terminal.println(gray("Expected format:"))
            terminal.println(
                gray(
                    """
                {
                  "repository": "owner/repo",
                  "issues": [
                    {
                      "localId": "unique-id",
                      "type": "Feature",
                      "title": "Issue title",
                      "body": "Issue description",
                      "labels": ["label1"],
                      "parent": null,
                      "dependsOn": []
                    }
                  ]
                }
                    """.trimIndent(),
                ),
            )
            return@runBlocking
        }

        // Display parsed issues
        terminal.println(cyan("Parsed ${request.issues.size} issues for ${request.repository}"))
        terminal.println()

        request.issues.forEach { issue ->
            val deps = if (issue.dependsOn.isNotEmpty()) {
                gray(" (depends: ${issue.dependsOn.joinToString()})")
            } else {
                ""
            }
            val parent = issue.parent?.let { gray(" [parent: $it]") } ?: ""

            terminal.println("  ${issue.type}: ${blue(issue.localId)} - ${issue.title}$parent$deps")
        }

        if (dryRun) {
            terminal.println()
            terminal.println(yellow("Dry run - no issues created"))
            return@runBlocking
        }

        terminal.println()
        terminal.println("Creating issues...")
        terminal.println()

        // Initialize provider and creator
        val provider = GitHubCliProvider()
        val batchCreator = BatchIssueCreator(provider)

        // Validate GitHub CLI connection
        val connectionResult = provider.validateConnection()
        if (connectionResult.isFailure) {
            terminal.println(red("GitHub CLI not authenticated"))
            terminal.println("Run: ${cyan("gh auth login")}")
            terminal.println(gray("Error: ${connectionResult.exceptionOrNull()?.message}"))
            return@runBlocking
        }

        // Create issues
        try {
            val response = batchCreator.createBatch(request)

            if (response.success) {
                terminal.println(green("✓ Successfully created ${response.created.size} issues:"))
                terminal.println()
                response.created.forEach { created ->
                    terminal.println("  ${blue("#${created.issueNumber}")}: ${cyan(created.url)}")
                }
            } else {
                terminal.println(yellow("⚠ Partial success:"))
                terminal.println("  Created: ${green("${response.created.size}")}")
                terminal.println("  Failed: ${red("${response.errors.size}")}")
                terminal.println()

                if (response.created.isNotEmpty()) {
                    terminal.println(green("Successfully created:"))
                    response.created.forEach { created ->
                        terminal.println("  ${blue("#${created.issueNumber}")}: ${cyan(created.url)}")
                    }
                    terminal.println()
                }

                if (response.errors.isNotEmpty()) {
                    terminal.println(red("Errors:"))
                    response.errors.forEach { error ->
                        terminal.println("  ${error.localId}: ${error.message}")
                    }
                }
            }
        } catch (e: Exception) {
            terminal.println(red("✗ Failed: ${e.message}"))
            terminal.println(gray(e.stackTraceToString()))
        }
    }
}
