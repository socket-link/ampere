package link.socket.ampere.repl

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * Tracks statistics for observation commands.
 *
 * This utility helps provide feedback about how long observations ran
 * and how many events/items were processed.
 */
class ObservationStats {
    private var startTime: Instant? = null
    private var eventCount: Int = 0

    /**
     * Start tracking statistics.
     */
    fun start() {
        startTime = Clock.System.now()
        eventCount = 0
    }

    /**
     * Record that an event was observed.
     */
    fun recordEvent() {
        eventCount++
    }

    /**
     * Get a summary string of the observation session.
     */
    fun getSummary(): String {
        val start = startTime ?: return "No data"
        val duration = Clock.System.now() - start

        val durationStr = formatDuration(duration)

        return "Observed $eventCount events in $durationStr"
    }

    /**
     * Format a duration in a human-readable way.
     */
    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.inWholeSeconds

        return when {
            totalSeconds < 60 -> "${totalSeconds}s"
            totalSeconds < 3600 -> {
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                "${minutes}m ${seconds}s"
            }
            else -> {
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                "${hours}h ${minutes}m ${seconds}s"
            }
        }
    }
}
