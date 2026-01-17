package link.socket.ampere.cli.watch

import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateRequest
import link.socket.ampere.cli.goal.GoalHandler
import link.socket.ampere.cli.help.CommandRegistry
import link.socket.ampere.cli.watch.presentation.SparkTransitionDirection
import link.socket.ampere.cli.watch.presentation.WatchPresenter
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.integrations.issues.BatchIssueCreator
import link.socket.ampere.integrations.issues.github.GitHubCliProvider
import link.socket.ampere.renderer.SparkColors
import java.io.File

/**
 * Executes commands entered in command mode.
 *
 * This provides a vim-like command interface for the dashboard,
 * allowing users to perform actions and queries via text commands.
 */
class CommandExecutor(
    private val presenter: WatchPresenter,
    private val goalHandler: GoalHandler? = null,
    private val onGoalActivated: (() -> Unit)? = null,
) {

    /**
     * Execute a command and return the result.
     *
     * @param commandInput The full command string (without the leading ':')
     * @return The result of executing the command
     */
    suspend fun execute(commandInput: String): CommandResult {
        val trimmed = commandInput.trim()
        if (trimmed.isEmpty()) {
            return CommandResult.Error("Empty command")
        }

        val parts = trimmed.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val arg = parts.getOrNull(1)

        return when (command) {
            "help", "h", "?" -> executeHelp()
            "agents" -> executeAgents()
            "goal" -> executeGoal(arg)
            "issues" -> executeIssues(arg)
            "ticket" -> executeTicket(arg)
            "thread" -> executeThread(arg)
            "sparks", "spark", "cognitive" -> executeSparks(arg)
            "quit", "q", "exit" -> CommandResult.Quit
            else -> CommandResult.Error("Unknown command: $command\nType :help for available commands")
        }
    }

    private fun executeHelp(): CommandResult {
        val helpText = buildString {
            appendLine("Available commands:")
            appendLine()
            CommandRegistry.commands.forEach { cmd ->
                val formatted = cmd.formatForHelp().padEnd(26)
                appendLine("  $formatted ${cmd.description}")
            }
            appendLine()
            appendLine("Press ESC to cancel command mode")
        }
        return CommandResult.Success(helpText)
    }

    private suspend fun executeGoal(goalText: String?): CommandResult {
        if (goalText.isNullOrBlank()) {
            return CommandResult.Error("Usage: :goal <description>\n\nExample: :goal Implement FizzBuzz in Kotlin")
        }

        if (goalHandler == null) {
            return CommandResult.Error("Goal handler not available.\nTry starting with: ampere --goal \"$goalText\"")
        }

        return try {
            val result = goalHandler.activateGoal(goalText)
            if (result.isSuccess) {
                val activation = result.getOrNull()!!
                onGoalActivated?.invoke()
                CommandResult.Success(buildString {
                    appendLine("Goal activated!")
                    appendLine()
                    appendLine("  Title:  ${activation.title}")
                    appendLine("  Ticket: ${activation.ticketId}")
                    appendLine("  Agent:  ${activation.agentId}")
                    appendLine()
                    appendLine("The agent is now working on your goal.")
                    appendLine("Watch the progress pane to see the PROPEL cycle.")
                })
            } else {
                CommandResult.Error("Failed to activate goal: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            CommandResult.Error("Error activating goal: ${e.message}")
        }
    }

    private suspend fun executeIssues(arg: String?): CommandResult {
        if (arg.isNullOrBlank()) {
            return CommandResult.Error(
                "Usage: :issues create <filename>\n\n" +
                "Creates GitHub issues from a JSON file in .ampere/issues/\n\n" +
                "Example: :issues create cli-epic.json"
            )
        }

        val parts = arg.trim().split(" ", limit = 2)
        val subcommand = parts[0].lowercase()
        val filename = parts.getOrNull(1)

        return when (subcommand) {
            "create" -> executeIssuesCreate(filename)
            else -> CommandResult.Error(
                "Unknown issues subcommand: $subcommand\n\n" +
                "Available subcommands:\n" +
                "  create <filename>  Create issues from JSON file"
            )
        }
    }

    private suspend fun executeIssuesCreate(filename: String?): CommandResult {
        if (filename.isNullOrBlank()) {
            return CommandResult.Error(
                "Usage: :issues create <filename>\n\n" +
                "Example: :issues create cli-epic.json\n\n" +
                "The file should be in .ampere/issues/"
            )
        }

        // Resolve file path from .ampere/issues/
        val issuesDir = File(".ampere/issues")
        val file = File(issuesDir, filename)

        if (!file.exists()) {
            // List available files
            val availableFiles = if (issuesDir.exists()) {
                issuesDir.listFiles { f -> f.extension == "json" }
                    ?.map { it.name }
                    ?.sorted()
                    ?: emptyList()
            } else {
                emptyList()
            }

            return CommandResult.Error(buildString {
                appendLine("File not found: ${file.path}")
                appendLine()
                if (availableFiles.isNotEmpty()) {
                    appendLine("Available files in .ampere/issues/:")
                    availableFiles.forEach { appendLine("  $it") }
                } else {
                    appendLine("No JSON files found in .ampere/issues/")
                    appendLine("Create issue files in .ampere/issues/ first.")
                }
            })
        }

        // Parse JSON
        val json = try {
            file.readText()
        } catch (e: Exception) {
            return CommandResult.Error("Error reading file: ${e.message}")
        }

        val request = try {
            DEFAULT_JSON.decodeFromString<BatchIssueCreateRequest>(json)
        } catch (e: Exception) {
            return CommandResult.Error("Error parsing JSON: ${e.message}")
        }

        // Validate GitHub CLI connection
        val provider = GitHubCliProvider()
        val connectionResult = provider.validateConnection()
        if (connectionResult.isFailure) {
            return CommandResult.Error(buildString {
                appendLine("GitHub CLI not authenticated")
                appendLine()
                appendLine("Run: gh auth login")
                appendLine()
                appendLine("Error: ${connectionResult.exceptionOrNull()?.message}")
            })
        }

        // Create issues
        return try {
            val batchCreator = BatchIssueCreator(provider)
            val response = batchCreator.createBatch(request)

            if (response.success) {
                CommandResult.Success(buildString {
                    appendLine("Successfully created ${response.created.size} issues:")
                    appendLine()
                    response.created.forEach { created ->
                        appendLine("  #${created.issueNumber}: ${created.url}")
                    }
                })
            } else {
                CommandResult.Success(buildString {
                    appendLine("Partial success:")
                    appendLine("  Created: ${response.created.size}")
                    appendLine("  Failed: ${response.errors.size}")
                    appendLine()
                    if (response.created.isNotEmpty()) {
                        appendLine("Successfully created:")
                        response.created.forEach { created ->
                            appendLine("  #${created.issueNumber}: ${created.url}")
                        }
                        appendLine()
                    }
                    if (response.errors.isNotEmpty()) {
                        appendLine("Errors:")
                        response.errors.forEach { error ->
                            appendLine("  ${error.localId}: ${error.message}")
                        }
                    }
                })
            }
        } catch (e: Exception) {
            CommandResult.Error("Failed to create issues: ${e.message}")
        }
    }

    private fun executeAgents(): CommandResult {
        val viewState = presenter.getViewState()

        if (viewState.agentStates.isEmpty()) {
            return CommandResult.Success("No active agents")
        }

        val output = buildString {
            appendLine("Active Agents (${viewState.agentStates.size}):")
            appendLine()

            viewState.agentStates.values.sortedBy { it.displayName }.forEach { agent ->
                appendLine("  ${agent.displayName}")
                appendLine("    State: ${agent.currentState.displayText}")

                if (agent.consecutiveCognitiveCycles > 0) {
                    appendLine("    Cognitive cycles: ${agent.consecutiveCognitiveCycles}")
                }

                val timeSince = formatTimeSince(agent.lastActivityTimestamp)
                appendLine("    Last activity: $timeSince")
                appendLine()
            }
        }

        return CommandResult.Success(output)
    }

    private fun executeTicket(ticketId: String?): CommandResult {
        return if (ticketId == null) {
            CommandResult.Error("Usage: :ticket <id>")
        } else {
            CommandResult.Success(
                buildString {
                    appendLine("Ticket viewing is not yet implemented in this MVP.")
                    appendLine("This feature will be added in a future update.")
                    appendLine()
                    appendLine("Requested ticket ID: $ticketId")
                }
            )
        }
    }

    private fun executeThread(threadId: String?): CommandResult {
        return if (threadId == null) {
            CommandResult.Error("Usage: :thread <id>")
        } else {
            CommandResult.Success(
                buildString {
                    appendLine("Thread viewing is not yet implemented in this MVP.")
                    appendLine("This feature will be added in a future update.")
                    appendLine()
                    appendLine("Requested thread ID: $threadId")
                }
            )
        }
    }

    /**
     * Execute the :sparks command to inspect an agent's cognitive context.
     */
    private fun executeSparks(agentNameOrId: String?): CommandResult {
        val viewState = presenter.getViewState()

        // If no agent specified, show summary of all agents' cognitive states
        if (agentNameOrId.isNullOrBlank()) {
            if (viewState.agentStates.isEmpty()) {
                return CommandResult.Success("No active agents with cognitive state to display.")
            }

            val output = buildString {
                appendLine("╔══════════════════════════════════════════════════════════════╗")
                appendLine("║ COGNITIVE STATE SUMMARY                                       ║")
                appendLine("╠══════════════════════════════════════════════════════════════╣")

                viewState.agentStates.values.sortedBy { it.displayName }.forEach { agent ->
                    val affinityDisplay = agent.affinityName ?: "UNKNOWN"
                    val depthIndicator = SparkColors.renderDepthIndicator(agent.sparkDepth, SparkColors.DepthDisplayStyle.DOTS)
                    appendLine("║                                                               ║")
                    appendLine("║ ${agent.displayName.padEnd(30)} $depthIndicator".padEnd(64) + "║")

                    if (agent.affinityName != null) {
                        appendLine("║   Affinity: $affinityDisplay".padEnd(64) + "║")
                    }

                    if (agent.sparkNames.isNotEmpty()) {
                        val stackPreview = agent.sparkNames.joinToString(" → ").take(50)
                        appendLine("║   Stack: $stackPreview".padEnd(64) + "║")
                    } else {
                        appendLine("║   Stack: (no specialization)".padEnd(64) + "║")
                    }
                }

                appendLine("║                                                               ║")
                appendLine("╚══════════════════════════════════════════════════════════════╝")
                appendLine()
                appendLine("Use :sparks <agent-name> for detailed view of a specific agent.")
            }

            return CommandResult.Success(output)
        }

        // Find the agent by name (case-insensitive partial match)
        val agent = viewState.agentStates.values.find { agentState ->
            agentState.displayName.equals(agentNameOrId, ignoreCase = true) ||
            agentState.displayName.contains(agentNameOrId, ignoreCase = true) ||
            agentState.agentId.contains(agentNameOrId, ignoreCase = true)
        }

        if (agent == null) {
            val availableAgents = viewState.agentStates.values.map { it.displayName }.sorted()
            return CommandResult.Error(buildString {
                appendLine("Agent not found: $agentNameOrId")
                appendLine()
                if (availableAgents.isNotEmpty()) {
                    appendLine("Available agents:")
                    availableAgents.forEach { appendLine("  $it") }
                } else {
                    appendLine("No active agents.")
                }
            })
        }

        // Get cognitive state and history for this agent
        val cognitiveState = presenter.getCognitiveState(agent.agentId)
        val history = presenter.getSparkHistory(agent.agentId, 10)

        val output = buildString {
            appendLine("╔══════════════════════════════════════════════════════════════╗")
            appendLine("║ COGNITIVE CONTEXT: ${agent.displayName}".padEnd(64) + "║")
            appendLine("╠══════════════════════════════════════════════════════════════╣")
            appendLine("║                                                               ║")

            // Affinity section
            val affinityName = agent.affinityName ?: cognitiveState?.affinityName ?: "UNKNOWN"
            appendLine("║ Affinity: $affinityName".padEnd(64) + "║")
            appendLine("║                                                               ║")

            // Spark stack section
            appendLine("╠══════════════════════════════════════════════════════════════╣")
            appendLine("║ SPARK STACK                                                   ║")
            appendLine("║                                                               ║")

            val sparkNames = agent.sparkNames.ifEmpty { cognitiveState?.sparkNames ?: emptyList() }
            if (sparkNames.isEmpty()) {
                appendLine("║   (no specialization - operating at base affinity level)     ║")
            } else {
                sparkNames.forEachIndexed { index, sparkName ->
                    val isActive = index == sparkNames.lastIndex
                    val marker = if (isActive) " (ACTIVE)" else ""
                    val line = "║ [${index + 1}] $sparkName$marker"
                    appendLine(line.padEnd(64) + "║")
                }
            }

            appendLine("║                                                               ║")

            // History section
            if (history.isNotEmpty()) {
                appendLine("╠══════════════════════════════════════════════════════════════╣")
                appendLine("║ RECENT TRANSITIONS                                            ║")
                appendLine("║                                                               ║")

                history.reversed().forEach { transition ->
                    val time = formatTimeSince(transition.timestamp)
                    val icon = when (transition.direction) {
                        SparkTransitionDirection.APPLIED -> "+"
                        SparkTransitionDirection.REMOVED -> "-"
                    }
                    val line = "║   $time  $icon ${transition.sparkName.take(40)}"
                    appendLine(line.padEnd(64) + "║")
                }

                appendLine("║                                                               ║")
            }

            // Effective constraints section
            appendLine("╠══════════════════════════════════════════════════════════════╣")
            appendLine("║ EFFECTIVE STATE                                               ║")
            appendLine("║                                                               ║")
            appendLine("║ Depth: ${agent.sparkDepth}".padEnd(64) + "║")

            if (cognitiveState != null) {
                cognitiveState.effectivePromptLength?.let { length ->
                    appendLine("║ Prompt length: $length chars".padEnd(64) + "║")
                }
                cognitiveState.availableToolCount?.let { count ->
                    appendLine("║ Available tools: $count".padEnd(64) + "║")
                }
            }

            appendLine("║                                                               ║")
            appendLine("╚══════════════════════════════════════════════════════════════╝")
        }

        return CommandResult.Success(output)
    }

    private fun formatTimeSince(timestamp: kotlinx.datetime.Instant): String {
        val elapsed = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - timestamp.toEpochMilliseconds()
        return when {
            elapsed < 1000 -> "just now"
            elapsed < 60_000 -> "${elapsed / 1000}s ago"
            elapsed < 3600_000 -> "${elapsed / 60_000}m ago"
            elapsed < 86400_000 -> "${elapsed / 3600_000}h ago"
            else -> "${elapsed / 86400_000}d ago"
        }
    }
}
