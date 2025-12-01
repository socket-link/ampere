package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.magenta
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.white
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.agents.events.Event
import link.socket.ampere.agents.events.EventSource
import link.socket.ampere.agents.events.MeetingEvent
import link.socket.ampere.agents.events.MemoryEvent
import link.socket.ampere.agents.events.MessageEvent
import link.socket.ampere.agents.events.NotificationEvent
import link.socket.ampere.agents.events.TicketEvent
import link.socket.ampere.agents.events.ToolEvent
import link.socket.ampere.agents.events.Urgency

/**
 * Renders events to terminal with color coding and formatting.
 *
 * The goal is to create a visual language where you can quickly scan
 * and understand system activity. Colors and icons act as visual markers
 * for different event categories.
 *
 * Color coding:
 * - Event types: Green (tasks/tickets), Magenta (questions/meetings), Cyan (code),
 *   Blue (messages), White (notifications)
 * - Urgency levels: Red (HIGH), Yellow (MEDIUM), Gray (LOW)
 * - Other: Gray (timestamps, sources)
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
        val eventTypeName = event.eventType

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
     * - MemoryEvent: 🧠 cyan (knowledge/learning)
     * - ToolEvent: 🔧 yellow (tool registration/discovery)
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
            is MemoryEvent -> "🧠" to cyan
            is ToolEvent -> "🔧" to yellow
        }
    }

    /**
     * Extract human-readable information from the event.
     *
     * Each event type is parsed to show the most relevant information using
     * extension functions for better maintainability and scalability.
     */
    private fun extractSummary(event: Event): String = event.toSummary(::formatUrgency, ::formatSource)

    /**
     * Formats the event source for display.
     */
    private fun formatSource(source: EventSource): String {
        return when (source) {
            is EventSource.Agent -> source.agentId
            is EventSource.Human -> EventSource.Human.ID
        }
    }

    /**
     * Formats urgency level with color coding:
     * - HIGH: Red (needs immediate attention)
     * - MEDIUM: Yellow (should be addressed soon)
     * - LOW: Gray (can wait)
     */
    private fun formatUrgency(urgency: Urgency): String {
        return when (urgency) {
            Urgency.HIGH -> red("[HIGH]")
            Urgency.MEDIUM -> yellow("[MEDIUM]")
            Urgency.LOW -> gray("[LOW]")
        }
    }
}

/**
 * Extension functions for converting Event types to summary strings.
 * This pattern allows easy addition of new event types without modifying the renderer class.
 */
private fun Event.toSummary(
    formatUrgency: (Urgency) -> String,
    formatSource: (EventSource) -> String,
): String = when (this) {
    is Event.TaskCreated -> toSummary(formatUrgency, formatSource)
    is Event.QuestionRaised -> toSummary(formatUrgency, formatSource)
    is Event.CodeSubmitted -> toSummary(formatUrgency, formatSource)
    is MeetingEvent.MeetingScheduled -> toSummary(formatUrgency, formatSource)
    is MeetingEvent.MeetingStarted -> toSummary(formatUrgency)
    is MeetingEvent.AgendaItemStarted -> toSummary(formatUrgency)
    is MeetingEvent.AgendaItemCompleted -> toSummary(formatUrgency)
    is MeetingEvent.MeetingCompleted -> toSummary(formatUrgency)
    is MeetingEvent.MeetingCanceled -> toSummary(formatUrgency)
    is TicketEvent.TicketCreated -> toSummary(formatUrgency)
    is TicketEvent.TicketStatusChanged -> toSummary(formatUrgency)
    is TicketEvent.TicketAssigned -> toSummary(formatUrgency)
    is TicketEvent.TicketBlocked -> toSummary(formatUrgency)
    is TicketEvent.TicketCompleted -> toSummary(formatUrgency)
    is TicketEvent.TicketMeetingScheduled -> toSummary(formatUrgency)
    is MessageEvent.ThreadCreated -> toSummary(formatUrgency, formatSource)
    is MessageEvent.MessagePosted -> toSummary(formatUrgency, formatSource)
    is MessageEvent.ThreadStatusChanged -> toSummary(formatUrgency)
    is MessageEvent.EscalationRequested -> toSummary(formatUrgency)
    is NotificationEvent.ToAgent<*> -> toSummary(formatUrgency)
    is NotificationEvent.ToHuman<*> -> toSummary(formatUrgency)
    is MemoryEvent.KnowledgeStored -> toSummary(formatUrgency, formatSource)
    is MemoryEvent.KnowledgeRecalled -> toSummary(formatUrgency, formatSource)
    is ToolEvent.ToolRegistered -> toSummary(formatUrgency)
    is ToolEvent.ToolUnregistered -> toSummary(formatUrgency)
    is ToolEvent.ToolDiscoveryComplete -> toSummary(formatUrgency)
}

// Event.TaskCreated
private fun Event.TaskCreated.toSummary(
    formatUrgency: (Urgency) -> String,
    formatSource: (EventSource) -> String,
): String = buildString {
    append("Task #$taskId: $description")
    assignedTo?.let {
        append(" (assigned to: $it)")
    }
    append(" ${formatUrgency(urgency)}")
    append(" from ${formatSource(eventSource)}")
}

// Event.QuestionRaised
private fun Event.QuestionRaised.toSummary(
    formatUrgency: (Urgency) -> String,
    formatSource: (EventSource) -> String,
): String = buildString {
    append("\"$questionText\"")
    if (context.isNotBlank()) {
        append(" - Context: ${context.take(60)}")
        if (context.length > 60) append("...")
    }
    append(" ${formatUrgency(urgency)}")
    append(" from ${formatSource(eventSource)}")
}

// Event.CodeSubmitted
private fun Event.CodeSubmitted.toSummary(
    formatUrgency: (Urgency) -> String,
    formatSource: (EventSource) -> String,
): String = buildString {
    append(filePath)
    append(" - $changeDescription")
    if (reviewRequired) {
        append(" (review required)")
        assignedTo?.let {
            append(" for $it")
        }
    }
    append(" ${formatUrgency(urgency)}")
    append(" from ${formatSource(eventSource)}")
}

// MeetingEvent.MeetingScheduled
private fun MeetingEvent.MeetingScheduled.toSummary(
    formatUrgency: (Urgency) -> String,
    formatSource: (EventSource) -> String,
): String = "Meeting scheduled: ${meeting.invitation.title} ${formatUrgency(urgency)} from ${formatSource(eventSource)}"

// MeetingEvent.MeetingStarted
private fun MeetingEvent.MeetingStarted.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Meeting $meetingId started (thread: $threadId) ${formatUrgency(urgency)}"

// MeetingEvent.AgendaItemStarted
private fun MeetingEvent.AgendaItemStarted.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Agenda item started in meeting $meetingId ${formatUrgency(urgency)}"

// MeetingEvent.AgendaItemCompleted
private fun MeetingEvent.AgendaItemCompleted.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Agenda item $agendaItemId completed in meeting $meetingId ${formatUrgency(urgency)}"

// MeetingEvent.MeetingCompleted
private fun MeetingEvent.MeetingCompleted.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Meeting $meetingId completed with ${outcomes.size} outcomes ${formatUrgency(urgency)}"

// MeetingEvent.MeetingCanceled
private fun MeetingEvent.MeetingCanceled.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Meeting $meetingId canceled: $reason ${formatUrgency(urgency)}"

// TicketEvent.TicketCreated
private fun TicketEvent.TicketCreated.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Ticket $ticketId: $title ($ticketType, $priority) ${formatUrgency(urgency)}"

// TicketEvent.TicketStatusChanged
private fun TicketEvent.TicketStatusChanged.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Ticket $ticketId status: $previousStatus → $newStatus ${formatUrgency(urgency)}"

// TicketEvent.TicketAssigned
private fun TicketEvent.TicketAssigned.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Ticket $ticketId assigned to ${assignedTo ?: "unassigned"} ${formatUrgency(urgency)}"

// TicketEvent.TicketBlocked
private fun TicketEvent.TicketBlocked.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Ticket $ticketId blocked: $blockingReason ${formatUrgency(urgency)}"

// TicketEvent.TicketCompleted
private fun TicketEvent.TicketCompleted.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Ticket $ticketId completed by $eventSource ${formatUrgency(urgency)}"

// TicketEvent.TicketMeetingScheduled
private fun TicketEvent.TicketMeetingScheduled.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Meeting $meetingId scheduled for ticket $ticketId ${formatUrgency(urgency)}"

// MessageEvent.ThreadCreated
private fun MessageEvent.ThreadCreated.toSummary(
    formatUrgency: (Urgency) -> String,
    formatSource: (EventSource) -> String,
): String = "Thread created in ${thread.channel} ${formatUrgency(urgency)} from ${formatSource(eventSource)}"

// MessageEvent.MessagePosted
private fun MessageEvent.MessagePosted.toSummary(
    formatUrgency: (Urgency) -> String,
    formatSource: (EventSource) -> String,
): String = "Message posted in $channel ${formatUrgency(urgency)} from ${formatSource(eventSource)}"

// MessageEvent.ThreadStatusChanged
private fun MessageEvent.ThreadStatusChanged.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Thread $threadId status: $oldStatus → $newStatus ${formatUrgency(urgency)}"

// MessageEvent.EscalationRequested
private fun MessageEvent.EscalationRequested.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Escalation requested in thread $threadId: $reason ${formatUrgency(urgency)}"

// NotificationEvent.ToAgent
private fun NotificationEvent.ToAgent<*>.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Notification to $agentId ${formatUrgency(urgency)}"

// NotificationEvent.ToHuman
private fun NotificationEvent.ToHuman<*>.toSummary(
    formatUrgency: (Urgency) -> String,
): String = "Notification to human ${formatUrgency(urgency)}"

// MemoryEvent.KnowledgeStored
private fun MemoryEvent.KnowledgeStored.toSummary(
    formatUrgency: (Urgency) -> String,
    formatSource: (EventSource) -> String,
): String = buildString {
    append("Knowledge stored: $knowledgeType")
    taskType?.let { append(" ($it)") }
    if (tags.isNotEmpty()) {
        append(" [${tags.take(3).joinToString(", ")}]")
    }
    append(" ${formatUrgency(urgency)}")
    append(" from ${formatSource(eventSource)}")
}

// MemoryEvent.KnowledgeRecalled
private fun MemoryEvent.KnowledgeRecalled.toSummary(
    formatUrgency: (Urgency) -> String,
    formatSource: (EventSource) -> String,
): String = buildString {
    append("Knowledge recalled: $resultsFound result(s)")
    if (resultsFound > 0) {
        append(" (avg relevance: ${"%.2f".format(averageRelevance)})")
    }
    append(" ${formatUrgency(urgency)}")
    append(" from ${formatSource(eventSource)}")
}

// ToolEvent.ToolRegistered
private fun ToolEvent.ToolRegistered.toSummary(
    formatUrgency: (Urgency) -> String,
): String = buildString {
    append("Tool registered: $toolName")
    append(" (type: $toolType, autonomy: $requiredAutonomy)")
    mcpServerId?.let { append(" [server: $it]") }
    append(" ${formatUrgency(urgency)}")
}

// ToolEvent.ToolUnregistered
private fun ToolEvent.ToolUnregistered.toSummary(
    formatUrgency: (Urgency) -> String,
): String = buildString {
    append("Tool unregistered: $toolName")
    append(" - $reason")
    mcpServerId?.let { append(" [server: $it]") }
    append(" ${formatUrgency(urgency)}")
}

// ToolEvent.ToolDiscoveryComplete
private fun ToolEvent.ToolDiscoveryComplete.toSummary(
    formatUrgency: (Urgency) -> String,
): String = buildString {
    append("Tool discovery complete: $totalToolsDiscovered tool(s) found")
    append(" ($functionToolCount function, $mcpToolCount MCP)")
    if (mcpServerCount > 0) {
        append(" from $mcpServerCount server(s)")
    }
    append(" ${formatUrgency(urgency)}")
}
