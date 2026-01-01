package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.tickets.TicketSummary
import link.socket.ampere.cli.watch.presentation.CognitiveCluster
import link.socket.ampere.cli.watch.presentation.CognitiveClusterType
import link.socket.ampere.renderer.AmpereColors

/**
 * Central renderer for all CLI output.
 *
 * Handles rendering of:
 * - Events
 * - Tables (threads, tickets)
 * - Status messages
 * - Error messages
 *
 * This centralizes all Terminal interaction so commands don't need direct access.
 */
class CLIRenderer(
    private val terminal: Terminal,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) {
    private val eventRenderer = EventRenderer(terminal, timeZone)

    // ============================================================
    // Event Rendering
    // ============================================================

    fun renderEvent(event: Event) {
        eventRenderer.render(event)
    }

    // ============================================================
    // Watch Command Output
    // ============================================================

    fun renderWatchBanner() {
        terminal.println(bold(cyan("⚡ AMPERE")) + " - Connecting to AniMA Model Protocol virtual environment")
        terminal.println(dim("Connecting to event stream..."))
        terminal.println()
    }

    fun renderWatchStart() {
        terminal.println(bold("Watching events... (Ctrl+C to stop)"))
        terminal.println()
    }

    fun renderActiveFilters(filters: EventRelayFilters) {
        if (filters.isEmpty()) {
            terminal.println(dim("Watching all events (no filters)"))
        } else {
            terminal.println(dim("Active filters:"))
            filters.eventTypes?.let { types ->
                terminal.println(dim("  Event types: ${types.joinToString(", ")}"))
            }
            filters.eventSources?.let { sources ->
                terminal.println(dim("  Agents: ${sources.joinToString(", ") { (it as EventSource.Agent).agentId }}"))
            }
        }
        terminal.println()
    }

    fun renderWarning(message: String) {
        terminal.println(yellow("Warning: $message"))
    }

    fun renderAvailableEventTypes(types: Set<String>) {
        terminal.println(yellow("Available types: ${types.sorted().joinToString(", ")}"))
    }

    // ============================================================
    // Thread Command Output
    // ============================================================

    data class ThreadListItem(
        val threadId: String,
        val title: String,
        val messageCount: Int,
        val participantCount: Int,
        val lastActivity: Instant,
        val hasUnreadEscalations: Boolean
    )

    fun renderThreadList(threads: List<ThreadListItem>) {
        if (threads.isEmpty()) {
            terminal.println(dim("No active threads found"))
        } else {
            terminal.println(bold("Active Threads"))
            terminal.println()

            val table = table {
                header {
                    row("Thread ID", "Title", "Messages", "Participants", "Last Activity", "Status")
                }
                body {
                    threads.forEach { thread ->
                        row(
                            com.github.ajalt.mordant.rendering.TextColors.gray(thread.threadId),
                            cyan(thread.title),
                            thread.messageCount.toString(),
                            thread.participantCount.toString(),
                            formatTimestamp(thread.lastActivity, includeDate = true, includeSeconds = false),
                            if (thread.hasUnreadEscalations) {
                                red("⚠ ESCALATED")
                            } else {
                                com.github.ajalt.mordant.rendering.TextColors.green("Active")
                            }
                        )
                    }
                }
            }

            terminal.println(table)
            terminal.println()
            terminal.println(dim("Total: ${threads.size} active thread(s)"))
        }
    }

    data class ThreadMessage(
        val sender: MessageSender,
        val timestamp: Instant,
        val content: String
    )

    data class ThreadDetail(
        val threadId: String,
        val title: String,
        val participants: List<String>,
        val messages: List<ThreadMessage>
    )

    fun renderThreadDetail(thread: ThreadDetail) {
        terminal.println(bold(cyan("Thread: ${thread.title}")))
        terminal.println(dim("Thread ID: ${thread.threadId}"))
        terminal.println(dim("Participants: ${thread.participants.joinToString(", ")}"))
        terminal.println()

        if (thread.messages.isEmpty()) {
            terminal.println(dim("No messages in this thread"))
        } else {
            thread.messages.forEach { message ->
                val senderColor = when (message.sender) {
                    is MessageSender.Agent -> com.github.ajalt.mordant.rendering.TextColors.blue
                    is MessageSender.Human -> com.github.ajalt.mordant.rendering.TextColors.green
                }

                terminal.println(
                    bold(senderColor("${message.sender.getIdentifier()}")) +
                        " " +
                        dim("at ${formatTimestamp(message.timestamp, includeDate = true, includeSeconds = true)}")
                )
                terminal.println(message.content)
                terminal.println()
            }

            terminal.println(dim("Total: ${thread.messages.size} message(s)"))
        }
    }

    fun renderThreadNotFound(threadId: String) {
        terminal.println(red("Error: Thread '$threadId' not found"))
        terminal.println(yellow("Tip: Use 'ampere thread list' to see available threads"))
    }

    // ============================================================
    // Status Command Output
    // ============================================================

    fun renderStatusHeader(title: String) {
        terminal.println(bold(cyan(title)))
        terminal.println()
    }

    fun renderStatusSection(sectionTitle: String) {
        terminal.println(bold(sectionTitle))
    }

    fun renderEmptyStatus(message: String) {
        terminal.println(dim(message))
    }

    fun renderTicketTable(tickets: List<TicketSummary>) {
        val table = table {
            header {
                row("ID", "Title", "Status", "Priority", "Assigned")
            }
            body {
                tickets.forEach { ticket ->
                    row(
                        com.github.ajalt.mordant.rendering.TextColors.gray(ticket.ticketId),
                        ticket.title,
                        ticket.status,
                        ticket.priority,
                        ticket.assigneeId ?: dim("unassigned")
                    )
                }
            }
        }
        terminal.println(table)
        terminal.println()
    }

    // ============================================================
    // Generic Output
    // ============================================================

    fun renderError(message: String) {
        terminal.println(red("Error: $message"))
    }

    fun renderJson(json: String) {
        terminal.println(json)
    }

    fun renderBlankLine() {
        terminal.println()
    }

    // ============================================================
    // Cognitive Cluster Rendering
    // ============================================================

    /**
     * Render a cognitive cluster with tree characters showing grouped events.
     */
    fun renderCognitiveCluster(cluster: CognitiveCluster) {
        val timestamp = formatClusterTimestamp(cluster.startTimestamp)
        val agentName = extractAgentName(cluster.agentId)

        when (cluster.cycleType) {
            CognitiveClusterType.KNOWLEDGE_RECALL_STORE -> {
                // Header line with timestamp and cluster type
                terminal.println(dim(timestamp) + "  🧠  " + cyan("Cognitive Cycle") + " " + AmpereColors.accent("($agentName)"))

                // Render each event in the cluster with tree characters
                cluster.events.forEachIndexed { index, event ->
                    val isLast = index == cluster.events.lastIndex
                    val prefix = if (isLast) "     └─ " else "     ├─ "

                    val description = when (event) {
                        is MemoryEvent.KnowledgeRecalled -> {
                            "recalled ${event.resultsFound} item(s)" +
                                if (event.resultsFound > 0) {
                                    val roundedRelevance = ((event.averageRelevance * 100).toInt()) / 100.0
                                    " (avg relevance: $roundedRelevance)"
                                } else ""
                        }
                        is MemoryEvent.KnowledgeStored -> {
                            "stored ${event.knowledgeType} knowledge" +
                                (event.taskType?.let { " ($it)" } ?: "")
                        }
                        else -> event.eventType
                    }

                    terminal.println(dim(prefix + description))
                }
            }
            else -> {
                // Other cluster types (future extension)
                terminal.println(dim("$timestamp  📦  ${cluster.cycleType} from ") + AmpereColors.accent(agentName))
            }
        }
    }

    // ============================================================
    // Utilities
    // ============================================================

    private fun formatTimestamp(instant: Instant, includeDate: Boolean, includeSeconds: Boolean): String {
        val localDateTime = instant.toLocalDateTime(timeZone)
        return buildString {
            if (includeDate) {
                append("${localDateTime.date} ")
            }
            append("${localDateTime.hour}:${localDateTime.minute.toString().padStart(2, '0')}")
            if (includeSeconds) {
                append(":${localDateTime.second.toString().padStart(2, '0')}")
            }
        }
    }

    private fun formatClusterTimestamp(instant: Instant): String {
        return formatTimestamp(instant, includeDate = false, includeSeconds = true)
    }

    /**
     * Extract a readable agent name from the agent ID.
     * Agent IDs follow pattern: UUID-AgentName
     */
    private fun extractAgentName(agentId: String): String {
        // Try to extract the agent name after the last hyphen
        val parts = agentId.split("-")
        return if (parts.size > 1 && parts.last().contains("Agent")) {
            parts.last()
        } else {
            // Fallback to last 16 characters
            agentId.takeLast(16)
        }
    }
}
