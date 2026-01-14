package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.magenta
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.table
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.knowledge.KnowledgeEntry
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.repl.TerminalFactory

/**
 * Root command for querying agent knowledge.
 *
 * Provides access to the system's accumulated learnings from agent operations.
 */
class KnowledgeCommand(
    knowledgeRepository: KnowledgeRepository,
) : CliktCommand(
    name = "knowledge",
    help = "Query agent knowledge and learnings",
) {
    init {
        subcommands(
            KnowledgeSearchCommand(knowledgeRepository),
            KnowledgeShowCommand(knowledgeRepository),
            KnowledgeStatsCommand(knowledgeRepository),
        )
    }

    override fun run() = Unit
}

/**
 * Search for knowledge entries using full-text search.
 *
 * The primary query: "What have agents learned about X?"
 */
class KnowledgeSearchCommand(
    private val knowledgeRepository: KnowledgeRepository,
) : CliktCommand(
    name = "search",
    help = "Search knowledge entries by text",
) {
    private val query by argument(
        "query",
        help = "Search terms to match against knowledge entries",
    )

    private val type by option("--type", "-t", help = "Filter by knowledge source type")
        .enum<KnowledgeType>()

    private val taskType by option("--task-type", help = "Filter by task type (e.g., 'database_migration')")

    private val tags by option("--tags", help = "Filter by tags (comma-separated)")
        .split(",")

    private val limit by option("--limit", "-n", help = "Maximum results to return")
        .int()
        .default(10)

    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()

        // Use contextual search if filters provided, otherwise simple search
        val result = if (type != null || taskType != null || !tags.isNullOrEmpty()) {
            knowledgeRepository.searchKnowledgeByContext(
                knowledgeType = type,
                taskType = taskType,
                tags = tags,
                limit = limit,
            )
        } else {
            knowledgeRepository.findSimilarKnowledge(query, limit)
        }

        result.fold(
            onSuccess = { entries ->
                if (entries.isEmpty()) {
                    terminal.println("No knowledge found for: $query")
                    return@fold
                }

                terminal.println(cyan("${bold("KNOWLEDGE SEARCH:")} \"$query\""))
                terminal.println(gray("${entries.size} results"))
                terminal.println()

                entries.forEach { entry ->
                    printKnowledgeEntry(terminal, entry, compact = true)
                }
            },
            onFailure = { error ->
                terminal.println(red("Error: ${error.message}"))
            },
        )
    }
}

/**
 * Show detailed information about a specific knowledge entry.
 */
class KnowledgeShowCommand(
    private val knowledgeRepository: KnowledgeRepository,
) : CliktCommand(
    name = "show",
    help = "Show details of a specific knowledge entry",
) {
    private val id by argument(
        "knowledge-id",
        help = "ID of the knowledge entry to display",
    )

    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()

        knowledgeRepository.getKnowledgeById(id).fold(
            onSuccess = { entry ->
                if (entry == null) {
                    terminal.println(red("Knowledge entry not found: $id"))
                    return@fold
                }

                // Get tags separately
                val tags = knowledgeRepository.getTagsForKnowledge(id).getOrDefault(emptyList())
                val entryWithTags = entry.copy(tags = tags)

                terminal.println(cyan(bold("KNOWLEDGE ENTRY")))
                terminal.println()
                printKnowledgeEntry(terminal, entryWithTags, compact = false)
            },
            onFailure = { error ->
                terminal.println(red("Error: ${error.message}"))
            },
        )
    }
}

/**
 * Show aggregate statistics about knowledge entries.
 */
class KnowledgeStatsCommand(
    private val knowledgeRepository: KnowledgeRepository,
) : CliktCommand(
    name = "stats",
    help = "Show knowledge statistics by type and source",
) {
    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()

        terminal.println(cyan(bold("KNOWLEDGE STATISTICS")))
        terminal.println()

        // Count by type
        var totalCount = 0
        val typeCounts = mutableMapOf<KnowledgeType, Int>()

        KnowledgeType.entries.forEach { type ->
            knowledgeRepository.findKnowledgeByType(type, limit = 1000).fold(
                onSuccess = { entries ->
                    typeCounts[type] = entries.size
                    totalCount += entries.size
                },
                onFailure = { /* ignore errors */ },
            )
        }

        if (totalCount == 0) {
            terminal.println(dim("No knowledge entries stored yet."))
            terminal.println()
            terminal.println("Knowledge is accumulated as agents work:")
            terminal.println("  - Run ${cyan("ampere run --demo jazz")} to generate sample knowledge")
            terminal.println("  - Or start autonomous work with ${cyan("ampere run --issues")}")
            return@runBlocking
        }

        terminal.println("Total entries: ${green(totalCount.toString())}")
        terminal.println()

        terminal.println(bold("By Source Type:"))
        terminal.println(
            table {
                header {
                    row("Type", "Count", "Description")
                }
                body {
                    typeCounts.entries
                        .sortedByDescending { it.value }
                        .forEach { (type, count) ->
                            if (count > 0) {
                                val description = when (type) {
                                    KnowledgeType.FROM_OUTCOME -> "Learnings from execution results"
                                    KnowledgeType.FROM_PLAN -> "Insights from planning phases"
                                    KnowledgeType.FROM_PERCEPTION -> "Observations from environment"
                                    KnowledgeType.FROM_IDEA -> "Extracted from ideation"
                                    KnowledgeType.FROM_TASK -> "Knowledge from task execution"
                                }
                                row(
                                    formatKnowledgeType(type),
                                    count.toString(),
                                    gray(description),
                                )
                            }
                        }
                }
            },
        )
    }
}

/**
 * Format a knowledge entry for display.
 */
private fun printKnowledgeEntry(
    terminal: com.github.ajalt.mordant.terminal.Terminal,
    entry: KnowledgeEntry,
    compact: Boolean,
) {
    val typeLabel = formatKnowledgeType(entry.knowledgeType)
    val timestamp = formatTimestamp(entry.timestamp)

    if (compact) {
        // Compact view for search results
        terminal.println("$typeLabel ${blue(entry.id.take(8))} ${gray(timestamp)}")
        terminal.println("  ${bold("Approach:")} ${entry.approach.take(80)}${if (entry.approach.length > 80) "..." else ""}")
        terminal.println("  ${bold("Learned:")} ${entry.learnings.take(80)}${if (entry.learnings.length > 80) "..." else ""}")
        if (entry.tags.isNotEmpty()) {
            terminal.println("  ${gray("Tags:")} ${entry.tags.joinToString(", ") { magenta(it) }}")
        }
        terminal.println()
    } else {
        // Full view for show command
        terminal.println("${bold("ID:")} ${blue(entry.id)}")
        terminal.println("${bold("Type:")} $typeLabel")
        terminal.println("${bold("Timestamp:")} $timestamp")
        entry.taskType?.let { terminal.println("${bold("Task Type:")} $it") }
        entry.complexityLevel?.let { terminal.println("${bold("Complexity:")} $it") }
        terminal.println()

        terminal.println(bold("Approach:"))
        terminal.println(entry.approach)
        terminal.println()

        terminal.println(bold("Learnings:"))
        terminal.println(entry.learnings)

        if (entry.tags.isNotEmpty()) {
            terminal.println()
            terminal.println("${bold("Tags:")} ${entry.tags.joinToString(", ") { magenta(it) }}")
        }

        // Show source IDs if present
        val sourceId = entry.ideaId ?: entry.outcomeId ?: entry.perceptionId ?: entry.planId ?: entry.taskId
        if (sourceId != null) {
            terminal.println()
            terminal.println("${bold("Source ID:")} ${gray(sourceId)}")
        }
    }
}

/**
 * Format knowledge type with color coding.
 */
private fun formatKnowledgeType(type: KnowledgeType): String {
    return when (type) {
        KnowledgeType.FROM_OUTCOME -> green("OUTCOME")
        KnowledgeType.FROM_PLAN -> blue("PLAN")
        KnowledgeType.FROM_PERCEPTION -> cyan("PERCEPTION")
        KnowledgeType.FROM_IDEA -> yellow("IDEA")
        KnowledgeType.FROM_TASK -> magenta("TASK")
    }
}

/**
 * Format timestamp for display.
 */
private fun formatTimestamp(instant: Instant): String {
    return instant.toString().take(19).replace("T", " ")
}
