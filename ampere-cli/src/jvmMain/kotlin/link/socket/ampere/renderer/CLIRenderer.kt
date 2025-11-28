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
import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.agents.events.relay.EventRelayFilters
import link.socket.ampere.agents.events.tickets.TicketSummary

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
                terminal.println(dim("  Event types: ${types.map { it.second }.joinToString(", ")}"))
            }
            filters.eventSources?.let { sources ->
                terminal.println(dim("  Agents: ${sources.map { (it as link.socket.ampere.agents.events.EventSource.Agent).agentId }.joinToString(", ")}"))
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
}
