package link.socket.ampere.renderer

import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.magenta
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.FileSystemEvent
import link.socket.ampere.agents.domain.event.GitEvent
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.NotificationEvent
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ProductEvent
import link.socket.ampere.agents.domain.event.TicketEvent
import link.socket.ampere.agents.domain.event.ToolEvent

/**
 * Renders events to terminal with color coding and formatting.
 *
 * The goal is to create a visual language where you can quickly scan
 * and understand system activity. Colors and icons act as visual markers
 * for different event categories.
 *
 * Color coding:
 * - Event types: Green (tasks/tickets), Magenta (questions/meetings), Cyan (code),
 *   Blue (messages), Gray (notifications - dimmed for less prominence)
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
     * Visual hierarchy (from most to least prominent):
     * - HIGH IMPORTANCE: TaskCreated, TicketEvent, QuestionRaised (green/magenta - actionable domain events)
     * - MEDIUM IMPORTANCE: CodeSubmitted, MeetingEvent, MessageEvent (cyan/magenta/blue - workflow events)
     * - LOW IMPORTANCE: NotificationEvent, ToolEvent, MemoryEvent (gray/yellow/cyan - meta/system events)
     *
     * Current mapping:
     * - TaskCreated: 📋 green (tasks/tickets)
     * - QuestionRaised: ❓ magenta (questions/escalations need attention)
     * - CodeSubmitted: 💻 cyan (code/technical events)
     * - MeetingEvent: 📅 magenta (meetings)
     * - TicketEvent: 🎫 green (tickets)
     * - MessageEvent: 💬 blue (messages)
     * - NotificationEvent: 🔔 gray (notifications - meta-events, less prominent)
     * - MemoryEvent: 🧠 cyan (knowledge/learning)
     * - ToolEvent: 🔧 yellow (tool registration/discovery)
     * - HumanInteractionEvent: 🙋 red (human input required - critical attention)
     */
    private fun getIconAndColor(event: Event): Pair<String, TextStyle> {
        return when (event) {
            is Event.CodeSubmitted -> "💻" to cyan
            is Event.QuestionRaised -> "❓" to magenta
            is Event.TaskCreated -> "📋" to green
            is FileSystemEvent -> "📄" to cyan
            is GitEvent -> "🌿" to green
            is HumanInteractionEvent -> "🙋" to red
            is MeetingEvent -> "📅" to magenta
            is MemoryEvent -> "🧠" to cyan
            is MessageEvent -> "💬" to blue
            is NotificationEvent<*> -> "🔔" to gray
            is PlanEvent -> "📋" to magenta
            is ProductEvent -> "💡" to green
            is TicketEvent -> "🎫" to green
            is ToolEvent -> "🔧" to yellow
        }
    }

    /**
     * Extract human-readable information from the event.
     *
     * Each event type is parsed to show the most relevant information using
     * extension functions for better maintainability and scalability.
     */
    private fun extractSummary(event: Event): String =
        event.getSummary(::formatUrgency, ::formatSource)

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
