package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.table
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.repl.TerminalFactory

/**
 * Root command for viewing outcome memory.
 *
 * Provides access to the environment's accumulated execution experience.
 */
class OutcomesCommand(
    outcomeRepository: OutcomeMemoryRepository,
) : CliktCommand(
    name = "outcomes",
    help = "View execution outcomes and accumulated experience",
) {
    init {
        subcommands(
            OutcomesTicketCommand(outcomeRepository),
            OutcomesSearchCommand(outcomeRepository),
            OutcomesExecutorCommand(outcomeRepository),
            OutcomesStatsCommand(outcomeRepository),
        )
    }

    override fun run() = Unit
}

/**
 * Show all execution attempts for a specific ticket.
 *
 * Useful for debugging: "This ticket keeps failing, what have we tried?"
 */
class OutcomesTicketCommand(
    private val outcomeRepository: OutcomeMemoryRepository,
) : CliktCommand(
    name = "ticket",
    help = "Show execution history for a specific ticket",
) {
    private val ticketId by argument(
        "ticket-id",
        help = "ID of the ticket to query",
    )

    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()

        outcomeRepository.getOutcomesByTicket(ticketId).fold(
            onSuccess = { outcomes ->
                if (outcomes.isEmpty()) {
                    terminal.println("No execution attempts found for ticket $ticketId")
                    return@fold
                }

                terminal.println(cyan("📊 EXECUTION HISTORY: $ticketId"))
                terminal.println(gray("${outcomes.size} attempts"))
                terminal.println()

                terminal.println(
                    table {
                        header {
                            row("Time", "Executor", "Result", "Duration", "Files", "Error")
                        }
                        body {
                            outcomes.forEach { outcome ->
                                val result = if (outcome.success) {
                                    green("✓ Success")
                                } else {
                                    red("✗ Failed")
                                }

                                val duration = formatDuration(outcome.executionDurationMs)

                                val error = if (outcome.success) {
                                    gray("-")
                                } else {
                                    yellow(outcome.errorMessage?.take(40) ?: "Unknown")
                                }

                                row(
                                    formatTimestamp(outcome.timestamp),
                                    blue(outcome.executorId),
                                    result,
                                    duration,
                                    outcome.filesChanged.toString(),
                                    error,
                                )
                            }
                        }
                    },
                )
            },
            onFailure = { error ->
                terminal.println(red("Error: ${error.message}"))
            },
        )
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds < 60) {
            "${seconds}s"
        } else {
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            "${minutes}m ${remainingSeconds}s"
        }
    }

    private fun formatTimestamp(instant: Instant): String {
        // Format as relative time would be nice: "2 hours ago"
        // For now, just use ISO format truncated
        return instant.toString().take(19)
    }
}

/**
 * Search for outcomes similar to a description.
 *
 * The learning query: "Has anyone tried something like this before?"
 */
class OutcomesSearchCommand(
    private val outcomeRepository: OutcomeMemoryRepository,
) : CliktCommand(
    name = "search",
    help = "Find outcomes similar to a description",
) {
    private val query by argument(
        "query",
        help = "Search query (keywords from ticket description)",
    )

    private val limit by option("--limit", "-n")
        .int()
        .default(10)

    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()

        outcomeRepository.findSimilarOutcomes(query, limit).fold(
            onSuccess = { outcomes ->
                if (outcomes.isEmpty()) {
                    terminal.println("No similar outcomes found for: $query")
                    return@fold
                }

                terminal.println(cyan("🔍 SIMILAR OUTCOMES: \"$query\""))
                terminal.println(gray("${outcomes.size} matches"))
                terminal.println()

                outcomes.forEach { outcome ->
                    val result = if (outcome.success) {
                        green("✓")
                    } else {
                        red("✗")
                    }

                    terminal.println("$result ${blue(outcome.ticketId)} - ${outcome.executorId}")
                    terminal.println("   ${gray(outcome.approach.take(100))}")
                    if (!outcome.success) {
                        terminal.println("   ${red("Error:")} ${outcome.errorMessage}")
                    }
                    terminal.println()
                }
            },
            onFailure = { error ->
                terminal.println(red("Error: ${error.message}"))
            },
        )
    }
}

/**
 * Show outcomes for a specific executor.
 *
 * Useful for analyzing executor performance.
 */
class OutcomesExecutorCommand(
    private val outcomeRepository: OutcomeMemoryRepository,
) : CliktCommand(
    name = "executor",
    help = "Show outcomes for a specific executor",
) {
    private val executorId by argument(
        "executor-id",
        help = "ID of the executor to analyze",
    )

    private val limit by option("--limit", "-n")
        .int()
        .default(20)

    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()

        outcomeRepository.getOutcomesByExecutor(executorId, limit).fold(
            onSuccess = { outcomes ->
                if (outcomes.isEmpty()) {
                    terminal.println("No outcomes found for executor: $executorId")
                    return@fold
                }

                // Calculate statistics
                val successCount = outcomes.count { it.success }
                val failureCount = outcomes.size - successCount
                val successRate = (successCount.toDouble() / outcomes.size * 100).toInt()
                val avgDuration = outcomes.map { it.executionDurationMs }.average().toLong()

                terminal.println(cyan("📊 EXECUTOR PERFORMANCE: $executorId"))
                terminal.println()
                terminal.println("Success rate: ${green("$successRate%")} ($successCount/${outcomes.size})")
                terminal.println("Average duration: ${formatDuration(avgDuration)}")
                terminal.println()

                terminal.println("Recent outcomes:")
                terminal.println(
                    table {
                        header {
                            row("Time", "Ticket", "Result", "Duration", "Files")
                        }
                        body {
                            outcomes.take(limit).forEach { outcome ->
                                val result = if (outcome.success) {
                                    green("✓")
                                } else {
                                    red("✗")
                                }

                                row(
                                    formatTimestamp(outcome.timestamp),
                                    blue(outcome.ticketId),
                                    result,
                                    formatDuration(outcome.executionDurationMs),
                                    outcome.filesChanged.toString(),
                                )
                            }
                        }
                    },
                )
            },
            onFailure = { error ->
                terminal.println(red("Error: ${error.message}"))
            },
        )
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds < 60) "${seconds}s" else "${seconds / 60}m"
    }

    private fun formatTimestamp(instant: Instant): String {
        return instant.toString().take(19)
    }
}

/**
 * Show aggregate statistics about outcomes.
 * Overall system learning and performance metrics.
 */
class OutcomesStatsCommand(
    private val outcomeRepository: OutcomeMemoryRepository,
) : CliktCommand(
    name = "stats",
    help = "Show aggregate outcome statistics",
) {
    // This would query aggregate data from the repository
    // For now, just a placeholder showing what it could display
    override fun run() {
        val terminal = TerminalFactory.createTerminal()
        terminal.println(cyan("📊 OUTCOME STATISTICS"))
        terminal.println()
        terminal.println(yellow("This command shows aggregate learning metrics:"))
        terminal.println("  - Overall success rate")
        terminal.println("  - Success rate by executor")
        terminal.println("  - Success rate by ticket category")
        terminal.println("  - Trends over time")
        terminal.println()
        terminal.println(dim("TODO: Implement aggregate queries"))
    }
}
