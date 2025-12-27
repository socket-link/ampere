package link.socket.ampere.cli.watch

import link.socket.ampere.cli.watch.presentation.WatchPresenter

/**
 * Executes commands entered in command mode.
 *
 * This provides a vim-like command interface for the dashboard,
 * allowing users to perform actions and queries via text commands.
 */
class CommandExecutor(
    private val presenter: WatchPresenter
) {

    /**
     * Execute a command and return the result.
     *
     * @param commandInput The full command string (without the leading ':')
     * @return The result of executing the command
     */
    fun execute(commandInput: String): CommandResult {
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
            "ticket" -> executeTicket(arg)
            "thread" -> executeThread(arg)
            "quit", "q", "exit" -> CommandResult.Quit
            else -> CommandResult.Error("Unknown command: $command\nType :help for available commands")
        }
    }

    private fun executeHelp(): CommandResult {
        val helpText = buildString {
            appendLine("Available commands:")
            appendLine()
            appendLine("  :help, :h, :?       Show this help")
            appendLine("  :agents             List all active agents")
            appendLine("  :ticket <id>        Show ticket details (coming soon)")
            appendLine("  :thread <id>        Show thread details (coming soon)")
            appendLine("  :quit, :q, :exit    Exit dashboard")
            appendLine()
            appendLine("Press ESC to cancel command mode")
        }
        return CommandResult.Success(helpText)
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
