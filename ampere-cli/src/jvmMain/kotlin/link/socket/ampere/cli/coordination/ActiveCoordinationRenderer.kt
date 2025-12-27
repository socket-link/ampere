package link.socket.ampere.cli.coordination

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.coordination.CoordinationState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Renders the active coordination panel showing current meetings, handoffs, and blockages.
 *
 * This renderer provides real-time situation awareness by displaying:
 * - Active meetings in progress
 * - Pending handoffs awaiting acknowledgment
 * - Blocked agents waiting on others
 */
class ActiveCoordinationRenderer {

    companion object {
        private const val EMPTY_MESSAGE = "No active coordination"
        private const val MEETING_PREFIX = "▶ Meeting:"
        private const val HANDOFF_PREFIX = "⧖ Pending Handoff:"
        private const val BLOCKED_PREFIX = "⛔ Blocked:"
    }

    /**
     * Render the active coordination panel.
     *
     * @param state Current coordination state
     * @param maxLines Maximum number of lines to render (0 = unlimited)
     * @return Rendered panel as string
     */
    fun render(state: CoordinationState, maxLines: Int = 0): String {
        val lines = mutableListOf<String>()
        val now = Clock.System.now()

        // Render active meetings
        state.activeMeetings.forEach { meeting ->
            lines.add("$MEETING_PREFIX \"${meeting.meeting.invitation.title}\"")

            val participants = meeting.participants.joinToString(", ") { shortenAgentName(it) }
            // Extract start time from meeting status
            val startTime = meeting.meeting.lastUpdatedAt() ?: now
            val duration = formatDuration(now - startTime)
            val messageCount = meeting.messageCount

            lines.add("    Participants: $participants    Started: $duration ago    Messages: $messageCount")
            lines.add("") // Empty line for spacing
        }

        // Render pending handoffs
        state.pendingHandoffs.forEach { handoff ->
            val source = shortenAgentName(handoff.fromAgentId)
            val target = shortenAgentName(handoff.toAgentId)

            lines.add("$HANDOFF_PREFIX $source → $target")

            val waiting = formatDuration(now - handoff.timestamp)
            lines.add("    Ticket: ${handoff.eventId}    Waiting: ${handoff.description} ($waiting)")
            lines.add("") // Empty line for spacing
        }

        // Render blocked agents
        state.blockedAgents.forEach { blocked ->
            val agentName = shortenAgentName(blocked.agentId)
            val blockedBy = shortenAgentName(blocked.blockedBy)
            val duration = formatDuration(now - blocked.since)

            lines.add("$BLOCKED_PREFIX $agentName waiting on $blockedBy")
            lines.add("    Reason: ${blocked.reason}    Duration: $duration")
            lines.add("") // Empty line for spacing
        }

        // Remove trailing empty line if present
        if (lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.removeLast()
        }

        // Handle empty state
        if (lines.isEmpty()) {
            return EMPTY_MESSAGE
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
     * Format a duration in human-readable form.
     *
     * @param duration Duration to format
     * @return Formatted string like "5s", "2m 30s", "1h 15m"
     */
    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.inWholeSeconds

        if (totalSeconds < 0) {
            return "0s"
        }

        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> {
                if (minutes > 0) {
                    "${hours}h ${minutes}m"
                } else {
                    "${hours}h"
                }
            }
            minutes > 0 -> {
                if (seconds > 0) {
                    "${minutes}m ${seconds}s"
                } else {
                    "${minutes}m"
                }
            }
            else -> "${seconds}s"
        }
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
