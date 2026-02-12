package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.cli.watch.presentation.CognitiveCluster
import link.socket.ampere.cli.watch.presentation.CognitiveClusterType
import link.socket.ampere.cli.watch.presentation.WatchViewState

/**
 * Renders the memory operations view - showing cognitive cycles,
 * knowledge recall/storage patterns, and memory access statistics.
 *
 * This view is like looking at the system's "working memory" - what
 * it's thinking about, what it's remembering, and what it's learning.
 */
class MemoryOpsRenderer(
    private val terminal: Terminal,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) {

    fun render(viewState: WatchViewState, clusters: List<CognitiveCluster>): String {
        return buildString {
            // Clear screen and move cursor to home
            append("\u001B[2J") // Clear screen
            append("\u001B[H")  // Move cursor to home

            // Header
            appendHeader()
            append("\n\n")

            // System memory stats
            appendStats(clusters, viewState)
            append("\n")

            // Recent cognitive cycles
            appendCognitiveCycles(clusters)
            append("\n")

            // Footer with shortcuts
            appendFooter()
        }
    }

    private fun StringBuilder.appendHeader() {
        append(terminal.render(bold(TextColors.cyan("Memory Operations"))))
    }

    private fun StringBuilder.appendStats(clusters: List<CognitiveCluster>, viewState: WatchViewState) {
        val totalCycles = clusters.size
        val knowledgeCycles = clusters.count { it.cycleType == CognitiveClusterType.KNOWLEDGE_RECALL_STORE }
        val activeThinkingAgents = viewState.agentStates.values.count { it.consecutiveCognitiveCycles > 0 }

        append(terminal.render(bold("System Memory Stats")))
        append("\n")
        append("  Total cognitive cycles: ")
        append(terminal.render(TextColors.yellow(totalCycles.toString())))
        append("\n")
        append("  Knowledge operations: ")
        append(terminal.render(TextColors.cyan(knowledgeCycles.toString())))
        append("\n")
        append("  Active thinking agents: ")
        append(terminal.render(TextColors.green(activeThinkingAgents.toString())))
        append("\n")
    }

    private fun StringBuilder.appendCognitiveCycles(clusters: List<CognitiveCluster>) {
        append(terminal.render(bold("Recent Cognitive Cycles")))
        append("\n")

        if (clusters.isEmpty()) {
            append(terminal.render(dim("No cognitive cycles yet")))
            append("\n")
            return
        }

        // Calculate how many cycles to show based on terminal height
        // Reserve space for header (2 lines) + stats (5 lines) + footer (2 lines) = 9 lines
        // Each cycle takes ~3 lines (cluster header + events)
        val availableHeight = (terminal.info.height - 9).coerceAtLeast(6)
        val cyclesToShow = (availableHeight / 3).coerceAtLeast(5)

        clusters.take(cyclesToShow).forEach { cluster ->
            appendCluster(cluster)
        }
    }

    private fun StringBuilder.appendCluster(cluster: CognitiveCluster) {
        val clusterColor = when (cluster.cycleType) {
            CognitiveClusterType.KNOWLEDGE_RECALL_STORE -> TextColors.cyan
            CognitiveClusterType.TASK_PROCESSING -> TextColors.green
            CognitiveClusterType.MEETING_PARTICIPATION -> TextColors.magenta
        }

        val icon = when (cluster.cycleType) {
            CognitiveClusterType.KNOWLEDGE_RECALL_STORE -> "🧠"
            CognitiveClusterType.TASK_PROCESSING -> "⚙️"
            CognitiveClusterType.MEETING_PARTICIPATION -> "📅"
        }

        val timeStr = formatTime(cluster.startTimestamp)
        val durationStr = formatDuration(cluster.durationMillis)

        // Cluster header
        append(terminal.render(dim(timeStr)))
        append(" ")
        append(icon)
        append(" ")
        append(terminal.render(clusterColor(cluster.cycleType.name.lowercase().replace('_', ' '))))
        append(" ")
        append(terminal.render(dim("by ${cluster.agentId} ($durationStr, ${cluster.events.size} ops)")))
        append("\n")

        // Show memory event details
        cluster.events.forEach { event ->
            when (event) {
                is MemoryEvent.KnowledgeRecalled -> {
                    append("  ")
                    append(terminal.render(TextColors.cyan("└─ Recalled: ")))
                    append("${event.resultsFound} result(s)")
                    if (event.resultsFound > 0) {
                        append(" (avg relevance: ${String.format("%.2f", event.averageRelevance)})")
                    }
                    append("\n")

                    // Show query context
                    if (event.context.description.isNotEmpty()) {
                        append("     ")
                        append(terminal.render(dim("Query: \"${event.context.description}\"")))
                        append("\n")
                    }

                    // Show top retrieved knowledge
                    event.retrievedKnowledge.take(2).forEachIndexed { index, knowledge ->
                        append("     ")
                        append(terminal.render(dim("[${String.format("%.2f", knowledge.relevanceScore)}] ")))

                        // Show full approach text, wrapping if needed
                        val lines = wrapText(knowledge.approach, maxWidth = 100)
                        lines.forEachIndexed { lineIndex, line ->
                            if (lineIndex > 0) {
                                append("         ") // Indent continuation lines
                            }
                            append(terminal.render(TextColors.white(line)))
                            append("\n")
                        }
                    }
                }
                is MemoryEvent.KnowledgeStored -> {
                    append("  ")
                    append(terminal.render(TextColors.green("└─ Stored: ")))
                    append(event.knowledgeType.toString().replace("FROM_", "").lowercase())
                    event.sourceId?.let { append(" [$it]") }
                    append("\n")

                    // Show what was stored
                    event.approach?.let { approach ->
                        append("     ")

                        // Show full approach text, wrapping if needed
                        val lines = wrapText(approach, maxWidth = 100)
                        lines.forEachIndexed { lineIndex, line ->
                            if (lineIndex > 0) {
                                append("     ") // Indent continuation lines
                            }
                            append(terminal.render(TextColors.white(line)))
                            append("\n")
                        }
                    }

                    // Show tags if available
                    if (event.tags.isNotEmpty()) {
                        append("     ")
                        append(terminal.render(dim("Tags: ${event.tags.take(3).joinToString(", ")}")))
                        append("\n")
                    }
                }
                else -> {
                    // Other event types - just show the type
                    append("  ")
                    append(terminal.render(dim("└─ ${event.eventType}")))
                    append("\n")
                }
            }
        }
    }

    private fun StringBuilder.appendFooter() {
        append(terminal.render(dim("d=dashboard  v=toggle verbose  h=help  Ctrl+C=exit")))
    }

    private fun formatTime(timestamp: kotlinx.datetime.Instant): String {
        val localDateTime = timestamp.toLocalDateTime(timeZone)
        return buildString {
            append(localDateTime.hour.toString().padStart(2, '0'))
            append(":")
            append(localDateTime.minute.toString().padStart(2, '0'))
            append(":")
            append(localDateTime.second.toString().padStart(2, '0'))
        }
    }

    private fun formatDuration(milliseconds: Long): String {
        return when {
            milliseconds < 1000 -> "${milliseconds}ms"
            milliseconds < 60_000 -> "${milliseconds / 1000}s"
            milliseconds < 3600_000 -> "${milliseconds / 60_000}m"
            else -> "${milliseconds / 3600_000}h"
        }
    }

    /**
     * Wrap text to fit within a maximum width, breaking at word boundaries.
     */
    private fun wrapText(text: String, maxWidth: Int): List<String> {
        if (text.length <= maxWidth) {
            return listOf(text)
        }

        val lines = mutableListOf<String>()
        val words = text.split(" ")
        var currentLine = StringBuilder()

        for (word in words) {
            if (currentLine.isEmpty()) {
                currentLine.append(word)
            } else if (currentLine.length + 1 + word.length <= maxWidth) {
                currentLine.append(" ").append(word)
            } else {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return lines
    }
}
