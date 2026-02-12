package link.socket.ampere.cli.coordination

import com.github.ajalt.mordant.rendering.TextColors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import link.socket.ampere.coordination.AgentInteraction
import link.socket.ampere.coordination.InteractionType
import link.socket.ampere.repl.TerminalSymbols

/**
 * Renders the interaction feed showing recent inter-agent coordination events.
 *
 * This renderer provides a chronological view of agent interactions with:
 * - Time-stamped entries with source → target formatting
 * - Color coding by interaction type
 * - Directional arrows indicating request vs response
 * - Verbose mode for detailed summaries
 */
class InteractionFeedRenderer {

    companion object {
        private const val EMPTY_MESSAGE = "No recent interactions"
        private const val SUMMARY_TRUNCATE_LENGTH = 30

        /** Get forward arrow based on terminal capabilities. */
        private fun forwardArrow(): String = TerminalSymbols.Arrow.forward

        /** Get backward arrow based on terminal capabilities. */
        private fun backwardArrow(): String = TerminalSymbols.Arrow.backward

        // Response types that use backward arrow
        private val RESPONSE_TYPES = setOf(
            InteractionType.CLARIFICATION_RESPONSE,
            InteractionType.HELP_RESPONSE,
            InteractionType.REVIEW_COMPLETE,
            InteractionType.HUMAN_RESPONSE,
        )
    }

    /**
     * Render the interaction feed.
     *
     * @param interactions List of recent interactions to display
     * @param verbose Whether to show full summaries
     * @param maxLines Maximum number of lines to render (0 = unlimited)
     * @return Rendered feed as string
     */
    fun render(
        interactions: List<AgentInteraction>,
        verbose: Boolean = false,
        maxLines: Int = 0,
    ): String {
        if (interactions.isEmpty()) {
            return EMPTY_MESSAGE
        }

        val lines = mutableListOf<String>()

        interactions.forEach { interaction ->
            val time = formatTime(interaction.timestamp)
            val source = shortenAgentName(interaction.sourceAgentId)
            val target = shortenAgentName(interaction.targetAgentId ?: "human")
            val arrow = getArrow(interaction.interactionType)
            val type = formatInteractionType(interaction.interactionType)
            val summary = interaction.context ?: ""

            // Build the main line
            val mainLine = buildString {
                append(time)
                append("  ")
                append(source.padEnd(12))
                append("  ")
                append(arrow)
                append("  ")
                append(target.padEnd(12))
                append("  ")
                append(type.padEnd(15))
                append(truncateSummary(summary, verbose))
            }

            lines.add(mainLine)

            // In verbose mode, show full summary on second line if truncated
            if (verbose && summary.length > SUMMARY_TRUNCATE_LENGTH) {
                lines.add("    $summary")
            }
        }

        // Handle maxLines overflow
        if (maxLines > 0 && lines.size > maxLines) {
            val visible = lines.take(maxLines - 1)
            val remaining = lines.size - visible.size
            return visible.joinToString("\n") + "\n... and $remaining more"
        }

        return lines.joinToString("\n")
    }

    /**
     * Format an instant as HH:MM:SS in local timezone.
     *
     * @param instant The timestamp to format
     * @return Formatted time string
     */
    private fun formatTime(instant: Instant): String {
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val hour = localDateTime.hour.toString().padStart(2, '0')
        val minute = localDateTime.minute.toString().padStart(2, '0')
        val second = localDateTime.second.toString().padStart(2, '0')
        return "$hour:$minute:$second"
    }

    /**
     * Format an interaction type as a short human-readable label.
     *
     * @param type The interaction type
     * @return Short label for the type
     */
    private fun formatInteractionType(type: InteractionType): String {
        return when (type) {
            InteractionType.TICKET_ASSIGNED -> "ticket"
            InteractionType.CLARIFICATION_REQUEST -> "clarify?"
            InteractionType.CLARIFICATION_RESPONSE -> "response"
            InteractionType.REVIEW_REQUEST -> "review?"
            InteractionType.REVIEW_COMPLETE -> "reviewed"
            InteractionType.MEETING_INVITE -> "meeting invite"
            InteractionType.MEETING_MESSAGE -> "meeting msg"
            InteractionType.HELP_REQUEST -> "help?"
            InteractionType.HELP_RESPONSE -> "helped"
            InteractionType.DELEGATION -> "delegated"
            InteractionType.HUMAN_ESCALATION -> "escalation"
            InteractionType.HUMAN_RESPONSE -> "human reply"
        }
    }

    /**
     * Get the arrow direction based on interaction type.
     * Respects terminal Unicode capabilities.
     *
     * @param type The interaction type
     * @return Arrow string (forward or backward)
     */
    private fun getArrow(type: InteractionType): String {
        return if (type in RESPONSE_TYPES) {
            backwardArrow()
        } else {
            forwardArrow()
        }
    }

    /**
     * Get the color for an interaction type.
     *
     * @param type The interaction type
     * @return Text color
     */
    private fun getColor(type: InteractionType): TextColors {
        return when (type) {
            InteractionType.TICKET_ASSIGNED -> TextColors.green
            InteractionType.MEETING_INVITE,
            InteractionType.MEETING_MESSAGE,
            -> TextColors.cyan
            InteractionType.HUMAN_ESCALATION -> TextColors.red
            InteractionType.HUMAN_RESPONSE -> TextColors.yellow
            InteractionType.REVIEW_REQUEST,
            InteractionType.REVIEW_COMPLETE,
            -> TextColors.magenta
            else -> TextColors.white
        }
    }

    /**
     * Truncate a summary if not in verbose mode.
     *
     * @param summary The summary text
     * @param verbose Whether to show full text
     * @return Truncated or full summary
     */
    private fun truncateSummary(summary: String, verbose: Boolean): String {
        if (verbose || summary.length <= SUMMARY_TRUNCATE_LENGTH) {
            return summary
        }
        return summary.take(SUMMARY_TRUNCATE_LENGTH - 3) + "..."
    }

    /**
     * Shorten agent name by removing "Agent" suffix.
     *
     * @param name Agent name to shorten
     * @return Shortened name
     */
    private fun shortenAgentName(name: String): String {
        return name
            .removeSuffix("Agent")
            .removeSuffix("agent")
    }
}
