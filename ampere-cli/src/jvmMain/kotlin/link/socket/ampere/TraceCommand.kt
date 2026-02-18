package link.socket.ampere

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.magenta
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.repl.TerminalFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Trace command shows an event and its surrounding context.
 *
 * Given an event ID, this command retrieves the event and shows
 * other events from the same source within a time window,
 * providing context for understanding what led to a decision.
 */
class TraceCommand(
    private val eventRepository: EventRepository,
) : CliktCommand(
    name = "trace",
    help = "Show an event and its surrounding context",
) {
    private val eventId by argument(
        "event-id",
        help = "ID of the event to trace",
    )

    private val window by option(
        "--window", "-w",
        help = "Time window in seconds to show surrounding events (default: 30)",
    ).int().default(30)

    override fun run() = runBlocking {
        val terminal = TerminalFactory.createTerminal()

        // First, get the target event
        eventRepository.getEventById(eventId).fold(
            onSuccess = { event ->
                if (event == null) {
                    terminal.println(red("Event not found: $eventId"))
                    terminal.println()
                    terminal.println("Tips:")
                    terminal.println("  - Event IDs are UUIDs like ${gray("a1b2c3d4-e5f6-7890-abcd-ef1234567890")}")
                    terminal.println("  - Run ${cyan("ampere")} to see events in the TUI dashboard")
                    terminal.println("  - Event IDs are shown in the event stream output")
                    return@fold
                }

                // Get the source agent ID
                val sourceId = event.eventSource.getIdentifier()

                // Calculate time window
                val windowDuration = window.seconds
                val fromTime = Instant.fromEpochMilliseconds(
                    event.timestamp.toEpochMilliseconds() - windowDuration.inWholeMilliseconds
                )
                val toTime = Instant.fromEpochMilliseconds(
                    event.timestamp.toEpochMilliseconds() + windowDuration.inWholeMilliseconds
                )

                // Get surrounding events from the same source
                eventRepository.getEventsWithFilters(
                    fromTime = fromTime,
                    toTime = toTime,
                    sourceIds = setOf(sourceId),
                ).fold(
                    onSuccess = { contextEvents ->
                        displayTrace(terminal, event, contextEvents, sourceId)
                    },
                    onFailure = { error ->
                        // Fall back to just showing the target event
                        terminal.println(yellow("Could not retrieve context events: ${error.message}"))
                        terminal.println()
                        displaySingleEvent(terminal, event)
                    },
                )
            },
            onFailure = { error ->
                terminal.println(red("Error retrieving event: ${error.message}"))
            },
        )
    }

    private fun displayTrace(
        terminal: com.github.ajalt.mordant.terminal.Terminal,
        targetEvent: Event,
        contextEvents: List<Event>,
        sourceId: String,
    ) {
        terminal.println(cyan(bold("EVENT TRACE")))
        terminal.println()
        terminal.println("${bold("Target:")} ${blue(targetEvent.eventId)}")
        terminal.println("${bold("Source:")} ${formatSource(targetEvent.eventSource)}")
        terminal.println("${bold("Window:")} $window seconds")
        terminal.println()

        if (contextEvents.isEmpty()) {
            terminal.println(dim("No surrounding events found in time window."))
            terminal.println()
            displaySingleEvent(terminal, targetEvent)
            return
        }

        // Sort events chronologically
        val sortedEvents = contextEvents.sortedBy { it.timestamp }

        // Group by event type for a cleaner display
        terminal.println(bold("Timeline (${sortedEvents.size} events):"))
        terminal.println(gray("─".repeat(60)))

        sortedEvents.forEach { event ->
            val isTarget = event.eventId == targetEvent.eventId
            val prefix = if (isTarget) ">>> " else "    "
            val highlight: (String) -> String = if (isTarget) { s -> bold(s) } else { s -> s }

            val time = formatTime(event.timestamp)
            val typeLabel = formatEventType(event.eventType)
            val summary = event.getSummary(
                formatUrgency = { urgency -> formatUrgency(urgency) },
                formatSource = { source -> formatSource(source) },
            ).take(60)

            val line = "$prefix$time $typeLabel"
            terminal.println(highlight(line))
            terminal.println("$prefix     ${gray(summary)}${if (summary.length >= 60) "..." else ""}")

            if (isTarget) {
                terminal.println("$prefix     ${dim("ID: ${event.eventId}")}")
            }
        }

        terminal.println(gray("─".repeat(60)))
        terminal.println()
        terminal.println(dim("Tip: Use ${cyan("ampere trace <id> --window 60")} to expand the time window"))
    }

    private fun displaySingleEvent(
        terminal: com.github.ajalt.mordant.terminal.Terminal,
        event: Event,
    ) {
        terminal.println(bold("Event Details:"))
        terminal.println("  ${bold("ID:")} ${event.eventId}")
        terminal.println("  ${bold("Type:")} ${formatEventType(event.eventType)}")
        terminal.println("  ${bold("Time:")} ${formatTimestamp(event.timestamp)}")
        terminal.println("  ${bold("Source:")} ${formatSource(event.eventSource)}")
        terminal.println("  ${bold("Urgency:")} ${formatUrgency(event.urgency)}")
        terminal.println()
        terminal.println("  ${bold("Summary:")}")
        terminal.println("  ${event.getSummary(
            formatUrgency = { formatUrgency(it) },
            formatSource = { formatSource(it) },
        )}")
    }

    private fun formatTime(instant: Instant): String {
        // Format as HH:MM:SS
        return instant.toString().substring(11, 19)
    }

    private fun formatTimestamp(instant: Instant): String {
        return instant.toString().take(19).replace("T", " ")
    }

    private fun formatEventType(type: String): String {
        return when {
            type.contains("Task") -> cyan(type)
            type.contains("Question") -> yellow(type)
            type.contains("Code") -> green(type)
            type.contains("Memory") || type.contains("Knowledge") -> magenta(type)
            type.contains("Error") || type.contains("Failed") -> red(type)
            else -> blue(type)
        }
    }

    private fun formatSource(source: EventSource): String {
        return when (source) {
            is EventSource.Agent -> cyan(source.agentId)
            is EventSource.Human -> green("human")
        }
    }

    private fun formatUrgency(urgency: Urgency): String {
        return when (urgency) {
            Urgency.LOW -> dim("[low]")
            Urgency.MEDIUM -> ""
            Urgency.HIGH -> yellow(bold("[HIGH]"))
        }
    }
}
