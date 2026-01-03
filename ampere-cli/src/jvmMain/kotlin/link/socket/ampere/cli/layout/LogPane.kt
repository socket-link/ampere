package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Renders a scrolling log pane showing real-time log messages.
 *
 * This pane captures log messages (from println, printStackTrace, etc.)
 * and displays them with timestamps and severity colors.
 *
 * Activated when verbose mode is enabled.
 */
class LogPane(
    private val terminal: Terminal,
    private val clock: Clock = Clock.System
) : PaneRenderer {

    /**
     * Log message with metadata.
     */
    data class LogEntry(
        val timestamp: Instant,
        val level: LogLevel,
        val message: String,
        val source: String? = null
    )

    /**
     * Log severity levels.
     */
    enum class LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    // Thread-safe log buffer
    private val logEntries = ConcurrentLinkedQueue<LogEntry>()
    private val maxEntries = 500 // Keep last 500 entries

    // Auto-scroll state
    private var scrollOffset = 0
    private var autoScroll = true

    /**
     * Add a log message.
     */
    fun log(level: LogLevel, message: String, source: String? = null) {
        val entry = LogEntry(
            timestamp = clock.now(),
            level = level,
            message = message,
            source = source
        )

        logEntries.add(entry)

        // Trim old entries
        while (logEntries.size > maxEntries) {
            logEntries.poll()
        }

        // Auto-scroll to bottom when new messages arrive
        if (autoScroll) {
            scrollOffset = 0
        }
    }

    /**
     * Log at DEBUG level.
     */
    fun debug(message: String, source: String? = null) =
        log(LogLevel.DEBUG, message, source)

    /**
     * Log at INFO level.
     */
    fun info(message: String, source: String? = null) =
        log(LogLevel.INFO, message, source)

    /**
     * Log at WARN level.
     */
    fun warn(message: String, source: String? = null) =
        log(LogLevel.WARN, message, source)

    /**
     * Log at ERROR level.
     */
    fun error(message: String, source: String? = null) =
        log(LogLevel.ERROR, message, source)

    /**
     * Scroll up in the log.
     */
    fun scrollUp(lines: Int = 1) {
        scrollOffset = (scrollOffset + lines).coerceAtMost(logEntries.size - 1)
        autoScroll = false
    }

    /**
     * Scroll down in the log.
     */
    fun scrollDown(lines: Int = 1) {
        scrollOffset = (scrollOffset - lines).coerceAtLeast(0)
        if (scrollOffset == 0) {
            autoScroll = true
        }
    }

    /**
     * Get current log count.
     */
    fun getLogCount(): Int = logEntries.size

    /**
     * Clear all log entries.
     */
    fun clear() {
        logEntries.clear()
        scrollOffset = 0
        autoScroll = true
    }

    override fun render(width: Int, height: Int): List<String> {
        val lines = mutableListOf<String>()

        // Title
        val title = "LOGS"
        val titlePadding = " ".repeat(((width - title.length - 2) / 2).coerceAtLeast(0))
        lines.add("╭─$titlePadding$title$titlePadding${"─".repeat(width - titlePadding.length * 2 - title.length - 2)}╮")

        // Status line
        val statusLine = if (autoScroll) {
            "Auto-scroll: ON  | Total: ${logEntries.size}"
        } else {
            "Auto-scroll: OFF | Offset: $scrollOffset | Total: ${logEntries.size}"
        }
        lines.add("│ ${statusLine.padEnd(width - 3)} │")
        lines.add("├${"─".repeat(width - 2)}┤")

        // Calculate visible entries
        val contentHeight = height - 4 // title + status + separator + bottom border
        val allEntries = logEntries.toList()

        val visibleEntries = if (allEntries.isEmpty()) {
            emptyList()
        } else {
            // Reverse to show newest at bottom
            val reversed = allEntries.reversed()
            val startIndex = scrollOffset
            val endIndex = (startIndex + contentHeight).coerceAtMost(reversed.size)
            reversed.subList(startIndex, endIndex).reversed() // Re-reverse for display
        }

        // Render log entries
        if (visibleEntries.isEmpty()) {
            // Empty state
            val emptyMsg = "No log messages yet. Logs will appear here when verbose mode is active."
            val centerLine = contentHeight / 2
            repeat(contentHeight) { i ->
                if (i == centerLine) {
                    val paddedMsg = emptyMsg.take(width - 6).padEnd(width - 4)
                    lines.add("│ ${terminal.render(dim(paddedMsg))} │")
                } else {
                    lines.add("│ ${"".padEnd(width - 4)} │")
                }
            }
        } else {
            visibleEntries.forEach { entry ->
                val formattedEntry = formatLogEntry(entry, width - 4)
                lines.add("│ $formattedEntry │")
            }

            // Pad remaining space
            val remaining = contentHeight - visibleEntries.size
            repeat(remaining) {
                lines.add("│ ${"".padEnd(width - 4)} │")
            }
        }

        // Bottom border
        lines.add("╰${"─".repeat(width - 2)}╯")

        return lines
    }

    /**
     * Format a log entry for display.
     */
    private fun formatLogEntry(entry: LogEntry, maxWidth: Int): String {
        // Format timestamp (HH:MM:SS)
        val instant = entry.timestamp.toString()
        val timePart = if (instant.length >= 19) {
            instant.substring(11, 19) // Extract HH:MM:SS
        } else {
            "00:00:00"
        }

        // Level badge with color
        val levelBadge = when (entry.level) {
            LogLevel.DEBUG -> terminal.render(TextColors.gray("[DBG]"))
            LogLevel.INFO -> terminal.render(TextColors.blue("[INF]"))
            LogLevel.WARN -> terminal.render(TextColors.yellow("[WRN]"))
            LogLevel.ERROR -> terminal.render(TextColors.red("[ERR]"))
        }

        // Build message
        val prefix = "$timePart $levelBadge"
        val prefixLength = 14 // "HH:MM:SS [XXX] "
        val messageMaxWidth = maxWidth - prefixLength

        val truncatedMessage = if (entry.message.length > messageMaxWidth) {
            entry.message.take(messageMaxWidth - 3) + "..."
        } else {
            entry.message.padEnd(messageMaxWidth)
        }

        return "$prefix $truncatedMessage"
    }
}
