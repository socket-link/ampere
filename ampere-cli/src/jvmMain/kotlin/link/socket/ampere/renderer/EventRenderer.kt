package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.magenta
import com.github.ajalt.mordant.rendering.TextColors.white
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.MeetingEvent
import link.socket.ampere.agents.events.MessageEvent
import link.socket.ampere.agents.events.NotificationEvent
import link.socket.ampere.agents.events.TicketEvent

/**
 * Renders events to terminal with color coding and formatting.
 *
 * The goal is to create a visual language where you can quickly scan
 * and understand system activity. Colors and icons act as visual markers
 * for different event categories.
 */
class EventRenderer(
    private val terminal: Terminal,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) {

    /**
     * Renders a single event to the terminal with color coding and formatting.
     *
     * The output format is:
     * [timestamp] [icon] [event type] [summary]
     *
     * Example:
     * 14:32:18  🤖  TaskCreated              Task #123: Implement authentication (assigned to: agent-auth)
     */
    fun render(event: Event) {
        // Format timestamp for readability (HH:MM:SS)
        val timestamp = formatTimestamp(event)

        // Choose icon and color based on event type
        val (icon, color) = getIconAndColor(event)

        // Get the event type name
        val eventTypeName = event.eventClassType.second

        // Build the formatted line
        val line = buildString {
            append(gray(timestamp))
            append("  ")
            append(icon)
            append("  ")
            append(color(eventTypeName.padEnd(25)))
            append("  ")

            // Extract a human-readable summary from the event
            val summary = extractSummary(event)
            append(summary)
        }

        terminal.println(line)
    }

    /**
     * Formats the event timestamp as HH:MM:SS in the configured timezone.
     */
    private fun formatTimestamp(event: Event): String {
        val localDateTime = event.timestamp.toLocalDateTime(timeZone)
        return buildString {
            append(localDateTime.hour.toString().padStart(2, '0'))
            append(":")
            append(localDateTime.minute.toString().padStart(2, '0'))
            append(":")
            append(localDateTime.second.toString().padStart(2, '0'))
        }
    }

    /**
     * Returns the appropriate icon and color for an event based on its type.
     *
     * Current mapping:
     * - TaskCreated: 📋 green (tasks/tickets)
     * - QuestionRaised: ❓ magenta (questions/escalations need attention)
     * - CodeSubmitted: 💻 cyan (code/technical events)
     * - MeetingEvent: 📅 magenta (meetings)
     * - TicketEvent: 🎫 green (tickets)
     * - MessageEvent: 💬 blue (messages)
     * - NotificationEvent: 🔔 white (notifications)
     */
    private fun getIconAndColor(event: Event): Pair<String, com.github.ajalt.mordant.rendering.TextStyle> {
        return when (event) {
            is Event.TaskCreated -> "📋" to green
            is Event.QuestionRaised -> "❓" to magenta
            is Event.CodeSubmitted -> "💻" to cyan
            is MeetingEvent -> "📅" to magenta
            is TicketEvent -> "🎫" to green
            is MessageEvent -> "💬" to blue
            is NotificationEvent<*> -> "🔔" to white
        }
    }

    /**
     * Extract human-readable information from the event.
     *
     * Each event type is parsed to show the most relevant information:
     * - TaskCreated: Task ID, description, and assignment
     * - QuestionRaised: Question text and context
     * - CodeSubmitted: File path and change description
     * - MeetingEvent: Meeting-specific details
     * - TicketEvent: Ticket-specific details
     * - MessageEvent: Message-specific details
     * - NotificationEvent: Notification-specific details
     */
    private fun extractSummary(event: Event): String {
        return when (event) {
            is Event.TaskCreated -> {
                buildString {
                    append("Task #${event.taskId}: ${event.description}")
                    event.assignedTo?.let {
                        append(" (assigned to: $it)")
                    }
                    append(" [${event.urgency}]")
                    append(" from ${formatSource(event.eventSource)}")
                }
            }
            is Event.QuestionRaised -> {
                buildString {
                    append("\"${event.questionText}\"")
                    if (event.context.isNotBlank()) {
                        append(" - Context: ${event.context.take(60)}")
                        if (event.context.length > 60) append("...")
                    }
                    append(" [${event.urgency}]")
                    append(" from ${formatSource(event.eventSource)}")
                }
            }
            is Event.CodeSubmitted -> {
                buildString {
                    append(event.filePath)
                    append(" - ${event.changeDescription}")
                    if (event.reviewRequired) {
                        append(" (review required)")
                        event.assignedTo?.let {
                            append(" for $it")
                        }
                    }
                    append(" [${event.urgency}]")
                    append(" from ${formatSource(event.eventSource)}")
                }
            }
            is MeetingEvent.MeetingScheduled -> "Meeting scheduled: ${event.meeting.invitation.title} [${event.urgency}] from ${formatSource(event.eventSource)}"
            is MeetingEvent.MeetingStarted -> "Meeting ${event.meetingId} started (thread: ${event.threadId}) [${event.urgency}]"
            is MeetingEvent.AgendaItemStarted -> "Agenda item started in meeting ${event.meetingId} [${event.urgency}]"
            is MeetingEvent.AgendaItemCompleted -> "Agenda item ${event.agendaItemId} completed in meeting ${event.meetingId} [${event.urgency}]"
            is MeetingEvent.MeetingCompleted -> "Meeting ${event.meetingId} completed with ${event.outcomes.size} outcomes [${event.urgency}]"
            is MeetingEvent.MeetingCanceled -> "Meeting ${event.meetingId} canceled: ${event.reason} [${event.urgency}]"
            is TicketEvent.TicketCreated -> "Ticket ${event.ticketId}: ${event.title} (${event.type}, ${event.priority}) [${event.urgency}]"
            is TicketEvent.TicketStatusChanged -> "Ticket ${event.ticketId} status: ${event.previousStatus} → ${event.newStatus} [${event.urgency}]"
            is TicketEvent.TicketAssigned -> "Ticket ${event.ticketId} assigned to ${event.assignedTo ?: "unassigned"} [${event.urgency}]"
            is TicketEvent.TicketBlocked -> "Ticket ${event.ticketId} blocked: ${event.blockingReason} [${event.urgency}]"
            is TicketEvent.TicketCompleted -> "Ticket ${event.ticketId} completed by ${event.completedBy} [${event.urgency}]"
            is TicketEvent.TicketMeetingScheduled -> "Meeting ${event.meetingId} scheduled for ticket ${event.ticketId} [${event.urgency}]"
            is MessageEvent.ThreadCreated -> "Thread created in ${event.thread.channel} [${event.urgency}] from ${formatSource(event.eventSource)}"
            is MessageEvent.MessagePosted -> "Message posted in ${event.channel} [${event.urgency}] from ${formatSource(event.eventSource)}"
            is MessageEvent.ThreadStatusChanged -> "Thread ${event.threadId} status: ${event.oldStatus} → ${event.newStatus} [${event.urgency}]"
            is MessageEvent.EscalationRequested -> "Escalation requested in thread ${event.threadId}: ${event.reason} [${event.urgency}]"
            is NotificationEvent.ToAgent<*> -> "Notification to ${event.agentId} [${event.urgency}]"
            is NotificationEvent.ToHuman<*> -> "Notification to human [${event.urgency}]"
        }
    }

    /**
     * Formats the event source for display.
     */
    private fun formatSource(source: EventSource): String {
        return when (source) {
            is EventSource.Agent -> source.agentId
            is EventSource.Human -> EventSource.Human.ID
        }
    }
}
