package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.SignificantEventSummary

/**
 * Event pane with rich content display and expandable details.
 *
 * Events are numbered 1-9 for keyboard selection. Pressing a number
 * expands that event to show full details.
 */
class RichEventPane(
    private val terminal: Terminal,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : PaneRenderer {

    /**
     * Rich event with headline and expandable details.
     */
    data class RichEvent(
        val index: Int,
        val timestamp: Instant,
        val eventType: String,
        val icon: String,
        val headline: String,
        val details: List<String>,
        val significance: EventSignificance,
        val sourceAgent: String
    )

    private var events: List<RichEvent> = emptyList()
    var expandedIndex: Int? = null
    var verboseMode: Boolean = false

    /**
     * Update with event summaries from the presenter.
     */
    fun updateEvents(summaries: List<SignificantEventSummary>) {
        events = summaries
            .take(9)
            .mapIndexed { index, summary ->
                summary.toRichEvent(index + 1)
            }
    }

    fun expandEvent(index: Int) {
        if (index in 1..events.size) {
            expandedIndex = index
        }
    }

    fun collapseEvent() {
        expandedIndex = null
    }

    override fun render(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()

        // Header with separator and spacing
        val headerText = if (verboseMode) "Event Stream (verbose)" else "Event Stream"
        lines.addAll(renderSectionHeader(headerText, width, terminal))

        // Filter events based on verbose mode
        val visibleEvents = if (verboseMode) {
            events
        } else {
            events.filter { it.significance != EventSignificance.ROUTINE }
        }

        if (visibleEvents.isEmpty()) {
            // Enhanced empty state with visual indicator
            lines.add("")
            lines.add(terminal.render(dim("  ○ ○ ○")))
            lines.add("")
            lines.add(terminal.render(dim("  Waiting for events...")))
            lines.add("")
            lines.add(terminal.render(dim("  Events will stream here")))
            lines.add(terminal.render(dim("  as the agent works on")))
            lines.add(terminal.render(dim("  the assigned task.")))
            lines.add("")

            if (!verboseMode && events.isNotEmpty()) {
                // There are routine events hidden
                lines.add(terminal.render(dim("  ─────────────────")))
                lines.add(terminal.render(dim("  ${events.size} routine event(s)")))
                lines.add(terminal.render(dim("  hidden. Press [v] to")))
                lines.add(terminal.render(dim("  enable verbose mode.")))
            } else if (!verboseMode) {
                lines.add(terminal.render(dim("  ─────────────────")))
                lines.add(terminal.render(dim("  Tip: Press [v] for")))
                lines.add(terminal.render(dim("  verbose mode to see")))
                lines.add(terminal.render(dim("  all event types.")))
            }
        } else {
            val expanded = expandedIndex?.let { idx -> visibleEvents.find { it.index == idx } }

            if (expanded != null) {
                // Show expanded event details
                lines.addAll(renderExpandedEvent(expanded, width))
            } else {
                // Show event list
                visibleEvents.forEach { event ->
                    lines.addAll(renderEventSummary(event, width))
                    if (lines.size >= height - 2) return@forEach
                }
            }
        }

        // Pad to height
        while (lines.size < height) {
            lines.add("")
        }

        return lines.take(height).map { it.fitToWidth(width) }
    }

    private fun renderEventSummary(event: RichEvent, width: Int): List<String> {
        val lines = mutableListOf<String>()

        // Significance indicator dot
        val sigIndicator = getSignificanceIndicator(event.significance)

        val indexColor = when (event.significance) {
            EventSignificance.CRITICAL -> TextColors.red
            EventSignificance.SIGNIFICANT -> TextColors.yellow
            EventSignificance.ROUTINE -> TextColors.gray
        }

        // First line: significance dot + index + icon + type + timestamp
        val indexStr = terminal.render(indexColor("${event.index}."))
        val timeStr = formatRelativeTime(event.timestamp)
        lines.add("$sigIndicator $indexStr ${event.icon} ${event.eventType} ${terminal.render(dim(timeStr))}")

        // Second line: headline (indented)
        val headlineMax = width - 5
        val headline = event.headline.take(headlineMax)
        lines.add("    ${terminal.render(dim(headline))}")

        // Third line: source agent (if not too wide)
        if (event.sourceAgent.isNotBlank() && width > 20) {
            val agentLabel = "    ${terminal.render(TextColors.cyan(event.sourceAgent))}"
            lines.add(agentLabel)
        }

        // Separator line (subtle dashed line)
        lines.add(terminal.render(dim("  ${"┄".repeat((width - 4).coerceAtLeast(3))}")))

        return lines
    }

    private fun getSignificanceIndicator(significance: EventSignificance): String {
        return when (significance) {
            EventSignificance.CRITICAL -> terminal.render(TextColors.red("●"))
            EventSignificance.SIGNIFICANT -> terminal.render(TextColors.yellow("●"))
            EventSignificance.ROUTINE -> terminal.render(dim("○"))
        }
    }

    private fun formatShortTime(timestamp: Instant): String {
        val local = timestamp.toLocalDateTime(timeZone)
        return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    }

    /**
     * Format timestamp as relative time for recent events, absolute for older.
     * Examples: "just now", "2m ago", "15:42"
     */
    private fun formatRelativeTime(timestamp: Instant): String {
        val now = kotlinx.datetime.Clock.System.now()
        val elapsed = now.toEpochMilliseconds() - timestamp.toEpochMilliseconds()

        return when {
            elapsed < 5_000 -> "now"
            elapsed < 60_000 -> "${elapsed / 1000}s"
            elapsed < 3600_000 -> "${elapsed / 60_000}m"
            else -> formatShortTime(timestamp)
        }
    }

    private fun renderExpandedEvent(event: RichEvent, width: Int): List<String> {
        val lines = mutableListOf<String>()

        val indexColor = when (event.significance) {
            EventSignificance.CRITICAL -> TextColors.red
            EventSignificance.SIGNIFICANT -> TextColors.yellow
            EventSignificance.ROUTINE -> TextColors.gray
        }

        // Header with event info
        val indexStr = terminal.render(bold(indexColor("${event.index}.")))
        lines.add("$indexStr ${event.icon} ${event.eventType}")
        lines.add(terminal.render(dim("─".repeat((width - 2).coerceAtLeast(1)))))
        lines.add("")

        // Timestamp and source
        lines.add(terminal.render(dim("Time: ${formatFullTime(event.timestamp)}")))
        lines.add(terminal.render(dim("From: ${event.sourceAgent}")))
        lines.add("")

        // Headline
        lines.add(terminal.render(bold(event.headline)))
        lines.add("")

        // Details
        if (event.details.isNotEmpty()) {
            event.details.forEach { detail ->
                // Word wrap long details
                val wrapped = wordWrap(detail, width - 2)
                wrapped.forEach { line ->
                    lines.add("  $line")
                }
            }
        }

        lines.add("")
        lines.add(terminal.render(dim("[ESC to collapse]")))

        return lines
    }

    private fun wordWrap(text: String, maxWidth: Int): List<String> {
        if (text.length <= maxWidth) return listOf(text)

        val lines = mutableListOf<String>()
        var remaining = text

        while (remaining.length > maxWidth) {
            val breakPoint = remaining.lastIndexOf(' ', maxWidth)
            if (breakPoint > 0) {
                lines.add(remaining.substring(0, breakPoint))
                remaining = remaining.substring(breakPoint + 1)
            } else {
                lines.add(remaining.substring(0, maxWidth))
                remaining = remaining.substring(maxWidth)
            }
        }
        if (remaining.isNotEmpty()) {
            lines.add(remaining)
        }

        return lines
    }

    private fun formatFullTime(timestamp: Instant): String {
        val local = timestamp.toLocalDateTime(timeZone)
        return "${local.hour.toString().padStart(2, '0')}:" +
               "${local.minute.toString().padStart(2, '0')}:" +
               "${local.second.toString().padStart(2, '0')}"
    }

    /**
     * Convert a SignificantEventSummary to a RichEvent.
     */
    private fun SignificantEventSummary.toRichEvent(index: Int): RichEvent {
        val icon = getEventIcon(eventType)
        // Truncate any UUIDs in the summary text for better readability
        val formattedSummary = IdFormatter.truncateUuidsInText(summaryText)

        return RichEvent(
            index = index,
            timestamp = timestamp,
            eventType = eventType,
            icon = icon,
            headline = formattedSummary.take(40),
            details = listOf(formattedSummary),
            significance = significance,
            sourceAgent = IdFormatter.formatAgentId(sourceAgentName)
        )
    }

    /**
     * Get an appropriate icon for each event type.
     */
    private fun getEventIcon(eventType: String): String {
        // Use single-width Unicode symbols to prevent column shifting
        return when {
            eventType.contains("Ticket", ignoreCase = true) && eventType.contains("Created", ignoreCase = true) -> "+"
            eventType.contains("Ticket", ignoreCase = true) && eventType.contains("Assigned", ignoreCase = true) -> "→"
            eventType.contains("Ticket", ignoreCase = true) && eventType.contains("Status", ignoreCase = true) -> "◉"
            eventType.contains("Ticket", ignoreCase = true) && eventType.contains("Blocked", ignoreCase = true) -> "!"
            eventType.contains("Message", ignoreCase = true) -> ">"
            eventType.contains("Thread", ignoreCase = true) -> "#"
            eventType.contains("Escalation", ignoreCase = true) -> "!"
            eventType.contains("Knowledge", ignoreCase = true) && eventType.contains("Recalled", ignoreCase = true) -> "<"
            eventType.contains("Knowledge", ignoreCase = true) && eventType.contains("Stored", ignoreCase = true) -> ">"
            eventType.contains("Meeting", ignoreCase = true) -> "@"
            eventType.contains("Feature", ignoreCase = true) -> "*"
            eventType.contains("Task", ignoreCase = true) -> "#"
            eventType.contains("Question", ignoreCase = true) -> "?"
            else -> "•"
        }
    }
}
